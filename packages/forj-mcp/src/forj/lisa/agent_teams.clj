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
  )
