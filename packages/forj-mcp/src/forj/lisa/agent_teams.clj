(ns forj.lisa.agent-teams
  "Bridge between LISA_PLAN.edn and Claude Code's native Agent Teams.

   forj does NOT manage teams directly. Instead:
   - SKILL.md instructs Claude (as team lead) how to create teams from plan checkpoints
   - TaskCompleted hook validates REPL gates before allowing checkpoint completion
   - TeammateIdle hook redirects idle teammates to next ready checkpoint
   - MCP tool (lisa_plan_to_tasks) generates task descriptions from the plan

   LISA_PLAN.edn remains the authoritative source of truth."
  (:require [clojure.string :as str]
            [forj.lisa.plan-edn :as plan-edn]))

(defn agent-teams-enabled?
  "Check if Agent Teams mode is enabled via FORJ_AGENT_TEAMS env var.
   Returns true when the env var is set to a truthy value (1, true, yes).
   Returns false otherwise, with no warning (user simply didn't enable it)."
  []
  (let [val (System/getenv "FORJ_AGENT_TEAMS")]
    (boolean (and val (#{"1" "true" "yes"} (str/lower-case val))))))

(defn agent-teams-available?
  "Check if Agent Teams feature is available for the current platform.
   For Claude: checks FORJ_AGENT_TEAMS and CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS env vars.
   For OpenCode: not yet supported.

   Accepts optional platform keyword (:claude or :opencode). Defaults to :claude.

   Returns a map with:
   - :available - true if Agent Teams can be used
   - :reason - explanation when not available (nil when available)"
  ([] (agent-teams-available? :claude))
  ([platform]
   (case platform
     :opencode
     {:available false
      :reason "Agent Teams is not yet supported in OpenCode. Use sequential or parallel Lisa Loop instead."}

     ;; Claude (default)
     (let [enabled? (agent-teams-enabled?)
           experimental-flag (System/getenv "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS")]
       (cond
         (not enabled?)
         {:available false
          :reason "FORJ_AGENT_TEAMS env var not set. Set FORJ_AGENT_TEAMS=1 to enable Agent Teams mode."}

         (not experimental-flag)
         {:available false
          :reason "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS not set. Enable Agent Teams in ~/.claude/settings.json under env."}

         :else
         {:available true
          :reason nil})))))

(defn team-name-for-plan
  "Generate a stable team name from a plan title.
   Uses a hash to ensure uniqueness while keeping names readable."
  [plan]
  (let [title-hash (Math/abs (hash (:title plan)))]
    (str "lisa-" title-hash)))

(defn- format-checkpoint-summary
  "Format a single checkpoint as a summary line."
  [checkpoint]
  (let [cp-id (name (:id checkpoint))
        status (name (:status checkpoint :pending))]
    (str "- **" cp-id "** [" status "] — " (:description checkpoint)
         (when (:file checkpoint) (str " (`" (:file checkpoint) "`)"))
         (when (seq (:depends-on checkpoint))
           (str " (depends on: " (str/join ", " (map name (:depends-on checkpoint))) ")"))
         (when (seq (:gates checkpoint))
           (str "\n  Gates: " (str/join " | " (:gates checkpoint)))))))

(defn checkpoint->task-description
  "Generate the task description for an Agent Teams task from a LISA_PLAN.edn checkpoint.

   Returns a map with :subject and :description suitable for Claude's TaskCreate tool.
   The description includes REPL validation workflow instructions so teammates
   know how to validate their work.

   Args:
   - checkpoint: A Lisa checkpoint map with :id, :description, etc.
   - opts: Map with optional context:
     - :plan-title - The Lisa plan title
     - :signs-content - Recent signs/learnings"
  [checkpoint {:keys [plan-title signs-content]}]
  (let [cp-id (name (:id checkpoint))
        cp-desc (:description checkpoint)
        cp-file (:file checkpoint)
        cp-acceptance (:acceptance checkpoint)
        cp-gates (:gates checkpoint)
        cp-deps (:depends-on checkpoint)]
    {:subject (str "Checkpoint: " cp-id)
     :description
     (str/join "\n\n"
               (filter some?
                       [(when plan-title
                          (str "Part of plan: **" plan-title "**"))
                        (str "**Task:** " cp-desc)
                        (when cp-file (str "**File:** " cp-file))
                        (when cp-acceptance (str "**Acceptance Criteria:** " cp-acceptance))
                        (when (seq cp-gates) (str "**Gates (auto-validated on completion):** " (str/join " | " cp-gates)))
                        (when (seq cp-deps) (str "**Depends on:** " (str/join ", " (map name cp-deps))))
                        "## Coordination"
                        (str "Before editing any file, use `reload_namespace` to pick up changes from other workers. "
                             "If multiple checkpoints target the same file, use SendMessage to coordinate with "
                             "the other worker(s) — agree on which functions/sections each of you owns. "
                             "Read the file before editing to avoid overwriting others' work.")
                        "## REPL Validation Workflow"
                        "After implementing, validate via REPL before marking the task complete:"
                        "1. `reload_namespace` — Reload changed namespaces to pick up edits"
                        "2. `eval_comment_block` — Run examples in (comment ...) blocks"
                        "3. `repl_eval` — Test specific expressions to confirm correctness"
                        (when (seq cp-gates)
                          (str "**Note:** The TaskCompleted hook will automatically run your gates ("
                               (str/join ", " cp-gates)
                               "). If gates fail, your task completion will be rejected with feedback."))
                        (when (seq signs-content)
                          (str "## Previous Learnings\n\n" signs-content))]))}))

(defn plan->team-config
  "Generate the full team configuration from a LISA_PLAN.edn.

   Reads the plan and returns structured data for Claude (as team lead) to use
   with native Agent Teams tools (TeamCreate, TaskCreate).

   Returns a map with:
   - :team-name - Suggested team name
   - :plan-title - Plan title
   - :tasks - Seq of {:subject :description :depends-on} for each pending checkpoint
   - :completed - Seq of checkpoint IDs already done
   - :total - Total checkpoint count

   Returns nil if no plan exists."
  [project-path]
  (when (plan-edn/plan-exists? project-path)
    (let [plan (plan-edn/read-plan project-path)
          signs (plan-edn/recent-signs plan 5)
          signs-content (when (seq signs)
                          (str/join "\n\n"
                                    (map (fn [s]
                                           (str "### Sign (iteration " (:iteration s) ")\n"
                                                "**Issue:** " (:issue s) "\n"
                                                "**Fix:** " (:fix s)))
                                         signs)))
          checkpoints (:checkpoints plan)
          pending (remove #(= :done (:status %)) checkpoints)
          done (filter #(= :done (:status %)) checkpoints)
          tasks (mapv (fn [cp]
                        (let [{:keys [subject description]} (checkpoint->task-description
                                                              cp {:plan-title (:title plan)
                                                                  :signs-content signs-content})]
                          {:subject subject
                           :description description
                           :depends-on (mapv #(str "Checkpoint: " (name %))
                                             (:depends-on cp []))}))
                      pending)]
      {:team-name (team-name-for-plan plan)
       :plan-title (:title plan)
       :tasks tasks
       :completed (mapv #(name (:id %)) done)
       :total (count checkpoints)})))

(defn find-checkpoint-for-task
  "Given a task subject, find the corresponding LISA_PLAN.edn checkpoint ID.

   Task subjects follow the convention 'Checkpoint: <checkpoint-id>'.
   Returns the checkpoint keyword ID (e.g., :password-hashing) or nil if
   no matching checkpoint is found.

   Used by the TaskCompleted hook to map task completions back to plan checkpoints."
  [project-path task-subject]
  (when (and task-subject (str/starts-with? task-subject "Checkpoint: "))
    (let [cp-name (subs task-subject (count "Checkpoint: "))
          cp-id (keyword cp-name)]
      (when (plan-edn/plan-exists? project-path)
        (let [plan (plan-edn/read-plan project-path)]
          (when (plan-edn/checkpoint-by-id plan cp-id)
            cp-id))))))

(comment
  ;; Test feature detection
  (agent-teams-enabled?)
  ;; => false (unless FORJ_AGENT_TEAMS is set)

  (agent-teams-available?)
  ;; => {:available false, :reason "FORJ_AGENT_TEAMS env var not set..."}

  ;; Test team name generation
  (team-name-for-plan {:title "Build user authentication"})
  ;; => "lisa-<hash>"

  ;; Test checkpoint->task-description
  (checkpoint->task-description
   {:id :password-hashing
    :description "Create password hashing module"
    :file "src/auth/password.clj"
    :acceptance "verify-password returns true for matching passwords"
    :status :pending
    :gates ["repl:(verify-password \"test\" (hash-password \"test\"))"]}
   {:plan-title "Build user authentication"})
  ;; => {:subject "Checkpoint: password-hashing", :description "..."}

  ;; Test checkpoint->task-description - minimal
  (checkpoint->task-description
   {:id :cleanup
    :description "Remove deprecated code"
    :status :pending}
   {})

  ;; Test plan->team-config
  ;; (plan->team-config ".")

  ;; Test find-checkpoint-for-task
  ;; (find-checkpoint-for-task "." "Checkpoint: password-hashing")

  ;; Test find-checkpoint-for-task - no match
  ;; (find-checkpoint-for-task "." "Some other task")
  ;; => nil

  ;; Test find-checkpoint-for-task - not a checkpoint subject
  ;; (find-checkpoint-for-task "." "Fix the bug")
  ;; => nil

  ;; Test format-checkpoint-summary
  (format-checkpoint-summary {:id :jwt-tokens
                              :description "Create JWT token module"
                              :file "src/auth/jwt.clj"
                              :depends-on [:password-hashing]
                              :status :pending
                              :gates ["repl:(verify-token (create-token {:user-id 1}))"]})
  )
