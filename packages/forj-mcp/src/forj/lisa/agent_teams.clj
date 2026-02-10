(ns forj.lisa.agent-teams
  "Agent Teams integration for Lisa Loop.

   Provides an optional execution mode that leverages Claude Code's
   Agent Teams feature for parallel checkpoint execution with
   inter-agent communication.

   Core lifecycle:
   - create-team-for-plan! - Create an Agent Team from a Lisa plan
   - cleanup-team! - Tear down team resources
   - sync-plan-to-tasks! - Push Lisa plan state to Agent Team tasks

   LISA_PLAN.edn remains the authoritative source of truth.
   Agent Team tasks are ephemeral and synced on each poll cycle."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [forj.lisa.plan-edn :as plan-edn]))

(def ^:private tasks-base-dir
  "Base directory for Agent Team task files."
  (str (fs/path (fs/home) ".claude" "tasks")))

(defn agent-teams-enabled?
  "Check if Agent Teams mode is enabled via FORJ_AGENT_TEAMS env var.
   Returns true when the env var is set to a truthy value (1, true, yes).
   Returns false otherwise, with no warning (user simply didn't enable it)."
  []
  (let [val (System/getenv "FORJ_AGENT_TEAMS")]
    (boolean (and val (#{"1" "true" "yes"} (str/lower-case val))))))

(defn agent-teams-available?
  "Check if Claude Code Agent Teams feature is available.
   Looks for CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS env var which Claude Code
   sets when the feature is enabled.

   Returns a map with:
   - :available - true if Agent Teams can be used
   - :reason - explanation when not available (nil when available)"
  []
  (let [enabled? (agent-teams-enabled?)
        experimental-flag (System/getenv "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS")]
    (cond
      (not enabled?)
      {:available false
       :reason "FORJ_AGENT_TEAMS env var not set. Set FORJ_AGENT_TEAMS=1 to enable Agent Teams mode."}

      (not experimental-flag)
      {:available false
       :reason "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS not set. Agent Teams feature may not be available in your Claude Code version."}

      :else
      {:available true
       :reason nil})))

(defn- team-name-for-plan
  "Generate a stable team name from a plan title.
   Uses a hash to ensure uniqueness while keeping names readable."
  [plan]
  (let [title-hash (Math/abs (hash (:title plan)))]
    (str "lisa-" title-hash)))

(defn- team-tasks-dir
  "Get the tasks directory for a team."
  [team-name]
  (str (fs/path tasks-base-dir team-name)))

(defn- checkpoint->task
  "Convert a Lisa checkpoint to an Agent Team task map."
  [checkpoint]
  {:id (name (:id checkpoint))
   :subject (str "Checkpoint: " (name (:id checkpoint)))
   :description (:description checkpoint)
   :status (case (:status checkpoint)
             :done "completed"
             :in-progress "in_progress"
             :failed "completed"
             "pending")
   :depends_on (mapv name (:depends-on checkpoint []))
   :metadata {:file (:file checkpoint)
              :acceptance (:acceptance checkpoint)
              :gates (:gates checkpoint)
              :lisa-checkpoint-id (name (:id checkpoint))}})

(defn- write-task-file!
  "Write a task as JSON to the team's tasks directory."
  [team-name task]
  (let [dir (team-tasks-dir team-name)
        file (str (fs/path dir (str (:id task) ".json")))]
    (fs/create-dirs dir)
    (spit file (json/generate-string task {:pretty true}))
    file))

(defn- read-task-files
  "Read all task JSON files from a team's tasks directory.
   Returns a seq of parsed task maps."
  [team-name]
  (let [dir (team-tasks-dir team-name)]
    (when (fs/exists? dir)
      (->> (fs/glob dir "*.json")
           (map (fn [f]
                  (try
                    (json/parse-string (slurp (str f)) true)
                    (catch Exception _ nil))))
           (remove nil?)))))

(defn create-team-for-plan!
  "Create an Agent Team from a Lisa plan.

   Sets up the team directory structure and creates task files
   for all checkpoints in the plan. Dependencies between checkpoints
   are mapped to task dependencies.

   Returns a map with:
   - :team-name - The team identifier
   - :tasks-dir - Path to the tasks directory
   - :task-count - Number of tasks created"
  [project-path plan]
  (let [team-name (team-name-for-plan plan)
        tasks-dir (team-tasks-dir team-name)
        checkpoints (:checkpoints plan)
        tasks (mapv checkpoint->task checkpoints)]
    ;; Clean up any existing team with same name
    (when (fs/exists? tasks-dir)
      (fs/delete-tree tasks-dir))
    ;; Create task files
    (doseq [task tasks]
      (write-task-file! team-name task))
    {:team-name team-name
     :tasks-dir tasks-dir
     :task-count (count tasks)}))

(defn cleanup-team!
  "Tear down an Agent Team and clean up its resources.

   Removes the team's tasks directory and all task files.
   Safe to call multiple times (idempotent).

   Returns true if cleanup was performed, false if team didn't exist."
  [team-name]
  (let [dir (team-tasks-dir team-name)]
    (if (fs/exists? dir)
      (do
        (fs/delete-tree dir)
        true)
      false)))

(defn sync-plan-to-tasks!
  "Sync LISA_PLAN.edn checkpoint state to Agent Team task files.

   Reads the current plan and updates task files to match
   checkpoint statuses. Creates new task files for any checkpoints
   that don't have corresponding tasks. Preserves task owner
   assignments from Agent Team operations.

   Returns a map with:
   - :updated - count of tasks updated
   - :created - count of new tasks created"
  [project-path team-name]
  (let [plan (plan-edn/read-plan project-path)
        checkpoints (:checkpoints plan)
        existing-tasks (into {} (map (fn [t] [(:id t) t])
                                     (read-task-files team-name)))
        results (reduce (fn [acc checkpoint]
                          (let [task-id (name (:id checkpoint))
                                existing (get existing-tasks task-id)
                                new-task (checkpoint->task checkpoint)]
                            (if existing
                              ;; Update existing: preserve owner, update status
                              (let [merged (merge new-task
                                                  (select-keys existing [:owner]))]
                                (write-task-file! team-name merged)
                                (update acc :updated inc))
                              ;; Create new
                              (do
                                (write-task-file! team-name new-task)
                                (update acc :created inc)))))
                        {:updated 0 :created 0}
                        checkpoints)]
    results))

(defn- task-status->checkpoint-status
  "Convert Agent Team task status string to Lisa checkpoint status keyword."
  [task-status]
  (case task-status
    "completed" :done
    "in_progress" :in-progress
    "pending" :pending
    ;; Default to pending for unknown statuses
    :pending))

(defn sync-tasks-to-plan!
  "Read Agent Team task completion status and update LISA_PLAN.edn accordingly.

   Reads task files from the team's tasks directory and compares their
   statuses against the current plan. When a task is marked 'completed'
   by the Agent Team, the corresponding checkpoint in LISA_PLAN.edn is
   updated to :done.

   Only transitions tasks forward (pending→in-progress→done). Does not
   revert completed checkpoints.

   Returns a map with:
   - :synced - count of checkpoints whose status was updated
   - :unchanged - count of checkpoints that didn't change
   - :details - seq of {:id :from :to} for each change"
  [project-path team-name]
  (let [plan (plan-edn/read-plan project-path)
        tasks (read-task-files team-name)
        task-by-id (into {} (map (fn [t] [(:id t) t]) tasks))
        ;; Status priority for forward-only transitions
        status-priority {:pending 0, :in-progress 1, :done 2, :failed 2}
        results (reduce
                 (fn [acc checkpoint]
                   (let [cp-id (name (:id checkpoint))
                         task (get task-by-id cp-id)
                         current-status (:status checkpoint)
                         task-status (when task
                                       (task-status->checkpoint-status (:status task)))]
                     (if (and task-status
                              (> (get status-priority task-status 0)
                                 (get status-priority current-status 0)))
                       ;; Task has progressed further than plan - update plan
                       (-> acc
                           (update :synced inc)
                           (update :details conj {:id (:id checkpoint)
                                                  :from current-status
                                                  :to task-status}))
                       ;; No change needed
                       (update acc :unchanged inc))))
                 {:synced 0 :unchanged 0 :details []}
                 (:checkpoints plan))]
    ;; Apply updates to plan
    (when (pos? (:synced results))
      (let [updated-plan (reduce
                          (fn [p {:keys [id to]}]
                            (let [updates (cond-> {:status to}
                                            (= to :done) (assoc :completed (str (java.time.Instant/now)))
                                            (= to :in-progress) (assoc :started (str (java.time.Instant/now))))]
                              (plan-edn/update-checkpoint p id updates)))
                          plan
                          (:details results))
            ;; Update overall plan status
            final-plan (assoc updated-plan
                              :status (if (plan-edn/all-complete? updated-plan)
                                        :complete
                                        :in-progress))]
        (plan-edn/write-plan! project-path final-plan)))
    results))

(defn- format-checkpoint-summary
  "Format a single checkpoint as a summary line for the team lead prompt."
  [checkpoint]
  (let [cp-id (name (:id checkpoint))
        status (name (:status checkpoint :pending))]
    (str "- **" cp-id "** [" status "] — " (:description checkpoint)
         (when (:file checkpoint) (str " (`" (:file checkpoint) "`)"))
         (when (seq (:depends-on checkpoint))
           (str " (depends on: " (str/join ", " (map name (:depends-on checkpoint))) ")"))
         (when (seq (:gates checkpoint))
           (str "\n  Gates: " (str/join " | " (:gates checkpoint)))))))

(defn build-team-lead-prompt
  "Build the system prompt for the Agent Team lead.

   The team lead coordinates parallel checkpoint execution, spawning
   teammates for ready checkpoints and synthesizing results. The prompt
   includes the full plan context, checkpoint details, and the protocol
   for marking checkpoints complete.

   Parameters:
   - plan: The Lisa plan map (from LISA_PLAN.edn)
   - signs-content: Optional string of previous learnings (signs)"
  [plan signs-content]
  (let [checkpoints (:checkpoints plan)
        done (filter #(= :done (:status %)) checkpoints)
        pending (filter #(= :pending (:status %)) checkpoints)
        in-progress (filter #(= :in-progress (:status %)) checkpoints)
        ready (plan-edn/ready-checkpoints plan)]
    (str/join "\n\n"
              (filter some?
                      [(str "# Lisa Loop Team Lead: " (:title plan))
                       "You are coordinating parallel checkpoint execution for this Lisa plan. Your job is to spawn teammates for ready checkpoints, monitor their progress, and ensure quality through REPL validation."
                       ;; Plan overview
                       (str "## Plan Overview\n\n"
                            "Total checkpoints: " (count checkpoints) "\n"
                            "Completed: " (count done) "\n"
                            "In progress: " (count in-progress) "\n"
                            "Pending: " (count pending) "\n"
                            "Ready to start: " (count ready))
                       ;; Checkpoint details
                       (str "## Checkpoints\n\n"
                            (str/join "\n" (map format-checkpoint-summary checkpoints)))
                       ;; Role instructions
                       (str "## Your Role\n\n"
                            "1. Spawn teammates for checkpoints that are ready (pending with all dependencies met)\n"
                            "2. Each teammate works on ONE checkpoint independently\n"
                            "3. Monitor progress via the task list\n"
                            "4. When a teammate completes, verify their REPL validation results\n"
                            "5. Handle failures by recording signs for future iterations")
                       ;; Completion protocol
                       (str "## Checkpoint Completion Protocol\n\n"
                            "When a teammate reports their checkpoint is done:\n\n"
                            "1. Verify their REPL validation passed (reload_namespace + eval_comment_block)\n"
                            "2. Use the `lisa_mark_checkpoint_done` MCP tool with the checkpoint ID\n"
                            "3. Check if completing this checkpoint unblocks new ones\n"
                            "4. Spawn new teammates for any newly ready checkpoints")
                       ;; REPL validation guidance
                       (str "## REPL Validation Requirements\n\n"
                            "Every checkpoint MUST be validated via REPL before completion:\n\n"
                            "1. `reload_namespace` — Reload changed namespaces to pick up edits\n"
                            "2. `eval_comment_block` — Run examples in (comment ...) blocks\n"
                            "3. `repl_eval` — Test specific expressions to confirm correctness\n\n"
                            "Do NOT accept a checkpoint as complete without REPL evidence.")
                       ;; Failure handling
                       (str "## Handling Failures\n\n"
                            "If a teammate is blocked or encounters errors:\n\n"
                            "1. Record a sign using `lisa_append_sign` with the issue and fix\n"
                            "2. Signs persist across iterations so future runs avoid the same mistake\n"
                            "3. If the checkpoint cannot proceed, report it as blocked")
                       ;; Signs
                       (when (seq signs-content)
                         (str "## Previous Learnings (Signs)\n\n"
                              "These are lessons from previous iterations. Apply them to avoid repeating mistakes:\n\n"
                              signs-content))]))))

(defn build-teammate-prompt
  "Build the spawn prompt for a checkpoint teammate.

   Generates a focused prompt that instructs the teammate to:
   1. Work on exactly one checkpoint
   2. Validate via REPL before reporting completion
   3. Message the team lead when done or blocked

   The prompt includes checkpoint-specific details (file, description,
   acceptance criteria, gates) and REPL validation workflow instructions.

   Args:
   - checkpoint: A Lisa checkpoint map with :id, :description, etc.
   - plan-context: A map with optional context:
     - :plan-title - The Lisa plan title (for context)
     - :signs-content - Recent signs/learnings to avoid repeating mistakes"
  [checkpoint {:keys [plan-title signs-content]}]
  (let [cp-id (name (:id checkpoint))
        cp-desc (:description checkpoint)
        cp-file (:file checkpoint)
        cp-acceptance (:acceptance checkpoint)
        cp-gates (:gates checkpoint)
        cp-deps (:depends-on checkpoint)]
    (str/join "\n\n"
              (filter some?
                      [(str "# Checkpoint: " cp-id)
                       (when plan-title
                         (str "Part of plan: **" plan-title "**"))
                       (str "**Task:** " cp-desc)
                       (when cp-file (str "**File:** " cp-file))
                       (when cp-acceptance (str "**Acceptance Criteria:** " cp-acceptance))
                       (when (seq cp-gates) (str "**Gates:** " (str/join " | " cp-gates)))
                       (when (seq cp-deps) (str "**Depends on:** " (str/join ", " (map name cp-deps))))
                       "## Workflow"
                       "1. Read the target file and understand existing code"
                       (when cp-file (str "2. Implement changes in `" cp-file "`"))
                       (str (if cp-file "3" "2") ". Validate via REPL before reporting completion:")
                       "   - `reload_namespace` to pick up your edits"
                       "   - `eval_comment_block` to run examples in (comment ...) blocks"
                       "   - `repl_eval` to test specific expressions"
                       "## Completion Protocol"
                       "- When checkpoint is complete and validated, message the team lead with:"
                       (str "  `CHECKPOINT_COMPLETE: " cp-id "`")
                       "- If you are blocked or need help, message the team lead with:"
                       (str "  `CHECKPOINT_BLOCKED: " cp-id " - <reason>`")
                       "- Do NOT work on other checkpoints. Focus only on this one."
                       (when (seq signs-content)
                         (str "## Previous Learnings\n\n" signs-content))]))))

(comment
  ;; Test team name generation
  (team-name-for-plan {:title "Build user authentication"})
  ;; => "lisa-<hash>"

  ;; Test checkpoint->task conversion
  (checkpoint->task {:id :password-hashing
                     :description "Create password hashing module"
                     :file "src/auth/password.clj"
                     :status :pending
                     :depends-on []
                     :gates ["repl:(verify-password \"test\" (hash-password \"test\"))"]})
  ;; => {:id "password-hashing", :subject "Checkpoint: password-hashing", ...}

  ;; Test create-team-for-plan!
  (let [plan {:title "Test plan"
              :checkpoints [{:id :step-1
                             :description "First step"
                             :file "src/foo.clj"
                             :status :pending}
                            {:id :step-2
                             :description "Second step"
                             :depends-on [:step-1]
                             :status :pending}]}]
    (create-team-for-plan! "." plan))
  ;; => {:team-name "lisa-<hash>", :tasks-dir "...", :task-count 2}

  ;; Test cleanup
  (cleanup-team! "lisa-nonexistent")
  ;; => false

  ;; Test sync
  ;; (sync-plan-to-tasks! "." "lisa-<team-name>")

  ;; Full lifecycle test
  (let [plan {:title "Lifecycle test"
              :status :in-progress
              :checkpoints [{:id :a :description "Step A" :status :pending}
                            {:id :b :description "Step B" :depends-on [:a] :status :pending}]
              :signs []}
        {:keys [team-name]} (create-team-for-plan! "." plan)
        tasks (read-task-files team-name)
        _ (cleanup-team! team-name)]
    {:team-name team-name
     :task-count (count tasks)
     :task-ids (mapv :id tasks)
     :cleaned-up? (not (fs/exists? (team-tasks-dir team-name)))})
  ;; => {:team-name "lisa-...", :task-count 2, :task-ids ["a" "b"], :cleaned-up? true}

  ;; Test build-team-lead-prompt - basic plan
  (build-team-lead-prompt
   {:title "Build user authentication"
    :status :in-progress
    :checkpoints [{:id :password-hashing
                   :description "Create password hashing module"
                   :file "src/auth/password.clj"
                   :status :done}
                  {:id :jwt-tokens
                   :description "Create JWT token module"
                   :file "src/auth/jwt.clj"
                   :depends-on [:password-hashing]
                   :status :pending
                   :gates ["repl:(verify-token (create-token {:user-id 1}))"]}
                  {:id :middleware
                   :description "Auth middleware"
                   :depends-on [:jwt-tokens]
                   :status :pending}]}
   nil)

  ;; Test build-team-lead-prompt - with signs
  (build-team-lead-prompt
   {:title "Test plan"
    :checkpoints [{:id :step-1 :description "Step one" :status :pending}]}
   "### Sign\n**Issue:** Wrong namespace\n**Fix:** Use buddy.sign.jwt")

  ;; Test format-checkpoint-summary
  (format-checkpoint-summary {:id :jwt-tokens
                              :description "Create JWT token module"
                              :file "src/auth/jwt.clj"
                              :depends-on [:password-hashing]
                              :status :pending
                              :gates ["repl:(verify-token (create-token {:user-id 1}))"]})

  ;; Test build-teammate-prompt - basic checkpoint
  (build-teammate-prompt
   {:id :password-hashing
    :description "Create password hashing module"
    :file "src/auth/password.clj"
    :status :pending}
   {:plan-title "Build user authentication"})

  ;; Test build-teammate-prompt - with gates and deps
  (build-teammate-prompt
   {:id :jwt-tokens
    :description "Create JWT token module"
    :file "src/auth/jwt.clj"
    :status :pending
    :acceptance "JWT tokens can be created and verified"
    :gates ["repl:(verify-token (create-token {:user-id 1}))"]
    :depends-on [:password-hashing]}
   {:plan-title "Build user authentication"
    :signs-content "### Sign\n**Issue:** Wrong namespace\n**Fix:** Use buddy.sign.jwt"})

  ;; Test build-teammate-prompt - minimal (no file, no plan title)
  (build-teammate-prompt
   {:id :cleanup
    :description "Remove deprecated code"
    :status :pending}
   {})

  ;; Test task-status->checkpoint-status conversion
  [(task-status->checkpoint-status "completed")   ;; => :done
   (task-status->checkpoint-status "in_progress")  ;; => :in-progress
   (task-status->checkpoint-status "pending")       ;; => :pending
   (task-status->checkpoint-status "unknown")]      ;; => :pending

  ;; Test sync-tasks-to-plan! - full lifecycle
  ;; 1. Create a plan with pending checkpoints
  ;; 2. Create team from plan (tasks start as pending)
  ;; 3. Manually update task file to "completed"
  ;; 4. sync-tasks-to-plan! should update plan checkpoint to :done
  (let [test-dir "/tmp/test-sync-tasks"
        _ (babashka.fs/create-dirs test-dir)
        plan {:title "Sync test"
              :status :in-progress
              :checkpoints [{:id :step-a
                             :description "Step A"
                             :status :pending}
                            {:id :step-b
                             :description "Step B"
                             :depends-on [:step-a]
                             :status :pending}]
              :signs []}
        _ (forj.lisa.plan-edn/write-plan! test-dir plan)
        {:keys [team-name]} (create-team-for-plan! test-dir plan)
        ;; Simulate agent completing step-a by writing task file with "completed"
        _ (write-task-file! team-name {:id "step-a"
                                       :subject "Checkpoint: step-a"
                                       :description "Step A"
                                       :status "completed"})
        ;; Now sync tasks back to plan
        result (sync-tasks-to-plan! test-dir team-name)
        ;; Read plan back to verify
        updated-plan (forj.lisa.plan-edn/read-plan test-dir)
        step-a-status (:status (forj.lisa.plan-edn/checkpoint-by-id updated-plan :step-a))
        step-b-status (:status (forj.lisa.plan-edn/checkpoint-by-id updated-plan :step-b))
        _ (cleanup-team! team-name)
        _ (babashka.fs/delete-tree test-dir)]
    {:result result
     :step-a-status step-a-status  ;; => :done
     :step-b-status step-b-status  ;; => :pending (unchanged)
     })
  ;; Expected: {:result {:synced 1, :unchanged 1, :details [{:id :step-a, :from :pending, :to :done}]},
  ;;            :step-a-status :done, :step-b-status :pending}

  ;; Test agent-teams-enabled? - checks FORJ_AGENT_TEAMS env var
  (agent-teams-enabled?)
  ;; => false (unless FORJ_AGENT_TEAMS is set in your environment)

  ;; Test agent-teams-available? - checks both env vars
  (agent-teams-available?)
  ;; => {:available false, :reason "FORJ_AGENT_TEAMS env var not set..."}

  ;; Test sync-tasks-to-plan! - no regression (completed stays completed)
  (let [test-dir "/tmp/test-sync-no-regress"
        _ (babashka.fs/create-dirs test-dir)
        plan {:title "No regress test"
              :status :in-progress
              :checkpoints [{:id :already-done
                             :description "Already done"
                             :status :done
                             :completed "2024-01-01T00:00:00Z"}]
              :signs []}
        _ (forj.lisa.plan-edn/write-plan! test-dir plan)
        {:keys [team-name]} (create-team-for-plan! test-dir plan)
        ;; Task says "pending" but plan says :done — should NOT revert
        _ (write-task-file! team-name {:id "already-done"
                                       :subject "Checkpoint: already-done"
                                       :description "Already done"
                                       :status "pending"})
        result (sync-tasks-to-plan! test-dir team-name)
        updated-plan (forj.lisa.plan-edn/read-plan test-dir)
        status (:status (forj.lisa.plan-edn/checkpoint-by-id updated-plan :already-done))
        _ (cleanup-team! team-name)
        _ (babashka.fs/delete-tree test-dir)]
    {:result result
     :status status})
  ;; Expected: {:result {:synced 0, :unchanged 1, :details []}, :status :done}
  )
