(ns forj.lisa.orchestrator
  "Lisa Loop orchestrator - spawns fresh Claude instances for each iteration.

   The orchestrator:
   1. Reads LISA_PLAN.md to find current checkpoint
   2. Spawns a Claude instance with focused prompt
   3. Waits for completion
   4. Reads output JSON for success/failure
   5. Repeats until all checkpoints done or max iterations reached"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [forj.lisa.plan :as plan]))

(def default-config
  {:max-iterations 20
   :allowed-tools "Bash,Edit,Read,Write,Glob,Grep,mcp__forj__*,mcp__claude-in-chrome__*"
   :log-dir ".forj/logs/lisa"
   :poll-interval-ms 5000
   ;; Pass user's MCP config so Chrome MCP is available for visual validation
   :mcp-config (str (fs/home) "/.claude.json")})

(defn- ensure-log-dir!
  "Create the log directory if it doesn't exist."
  [project-path config]
  (let [log-dir (str (fs/path project-path (:log-dir config)))]
    (fs/create-dirs log-dir)
    log-dir))

(defn- ui-checkpoint?
  "Check if checkpoint appears to be UI-related based on description."
  [description]
  (let [desc-lower (str/lower-case (or description ""))]
    (some #(str/includes? desc-lower %)
          ["ui" "view" "screen" "page" "component" "display" "show" "render"
           "mobile" "web" "frontend" "button" "form" "list" "card"])))

(defn- build-iteration-prompt
  "Build the prompt for a single iteration focused on the current checkpoint."
  [plan checkpoint signs-content]
  (let [plan-title (:title plan)
        cp-num (:number checkpoint)
        cp-desc (:description checkpoint)
        cp-file (:file checkpoint)
        cp-acceptance (:acceptance checkpoint)
        cp-validation (:validation checkpoint)
        is-ui? (ui-checkpoint? cp-desc)]
    (str/join "\n\n"
              (filter some?
                      [(str "# Lisa Loop: " plan-title)
                       ""
                       "You are working on a focused checkpoint. Complete this ONE task, then exit."
                       ""
                       (str "## Current Checkpoint: " cp-num)
                       (str "**Task:** " cp-desc)
                       (when cp-file (str "**File:** " cp-file))
                       (when cp-acceptance (str "**Acceptance Criteria:** " cp-acceptance))
                       (when cp-validation (str "**Validation:** " cp-validation))
                       ""
                       "## Instructions"
                       ""
                       "1. Read the current state - check LISA_PLAN.md and query the REPL"
                       "2. Implement the checkpoint task"
                       "3. Validate using REPL evaluation (reload_namespace, eval_comment_block)"
                       (when is-ui?
                         "4. **VISUAL VALIDATION REQUIRED - DO NOT SKIP**:
   You MUST take a screenshot and visually verify the UI before marking this checkpoint complete.
   Do NOT mark [DONE] based only on REPL validation - you need visual evidence.

   Steps:
   a) Use visual MCP tools (Chrome MCP, Playwright MCP, Mobile MCP - whichever is available)
   b) Navigate to the app URL (localhost:8081 for Expo, localhost:8080 for web)
   c) Take a screenshot of the rendered UI
   d) Verify the screenshot shows the expected UI matching acceptance criteria
   e) If UI doesn't match, fix the code and re-verify visually

   CHECKPOINT WILL BE REJECTED if you mark complete without screenshot verification.")
                       (str (if is-ui? "5" "4") ". When acceptance criteria are met, edit LISA_PLAN.md to mark this checkpoint [DONE]")
                       (str (if is-ui? "6" "5") ". Output 'CHECKPOINT_COMPLETE' when done, or 'CHECKPOINT_BLOCKED: <reason>' if stuck")
                       ""
                       (when (seq signs-content)
                         (str "## Previous Learnings (Signs)\n\n" signs-content))
                       ""
                       "Focus ONLY on this checkpoint. Do not work ahead."]))))

(defn- read-signs
  "Read the LISA_SIGNS.md file if it exists."
  [project-path]
  (let [signs-path (str (fs/path project-path "LISA_SIGNS.md"))]
    (when (fs/exists? signs-path)
      (slurp signs-path))))

(defn- append-sign!
  "Append a sign (learning) to LISA_SIGNS.md."
  [project-path iteration issue fix]
  (let [signs-path (str (fs/path project-path "LISA_SIGNS.md"))
        timestamp (str (java.time.Instant/now))
        sign-entry (str "\n## Sign " iteration " (" timestamp ")\n"
                        "**Issue:** " issue "\n"
                        "**Fix:** " fix "\n")]
    (spit signs-path sign-entry :append true)))

(defn- spawn-claude-iteration!
  "Spawn a Claude instance for one iteration. Returns the process.
   Uses stdin to pass prompt, avoiding shell quoting issues."
  [project-path prompt config log-file]
  ;; Pass prompt via stdin to avoid shell escaping issues
  ;; --dangerously-skip-permissions is REQUIRED for non-interactive mode
  ;; --mcp-config passes user config so Chrome MCP is available for visual validation
  (p/process {:dir project-path
              :in prompt
              :out (java.io.File. log-file)
              :err :stdout}
             "claude" "-p" "--output-format" "json"
             "--dangerously-skip-permissions"
             "--allowedTools" (:allowed-tools config)
             "--mcp-config" (:mcp-config config)))

(defn- wait-for-process
  "Wait for process to complete, polling at intervals. Returns exit code."
  [proc poll-interval-ms]
  (loop []
    (if (p/alive? proc)
      (do
        (Thread/sleep poll-interval-ms)
        (recur))
      (:exit @proc))))

(defn- parse-iteration-result
  "Parse the JSON output from a Claude iteration."
  [log-file]
  (try
    (let [content (slurp log-file)]
      (json/parse-string content true))
    (catch Exception e
      {:is_error true
       :error (str "Failed to parse output: " (.getMessage e))})))

(defn- iteration-succeeded?
  "Check if an iteration succeeded based on output."
  [result]
  (and (not (:is_error result))
       (= "success" (:subtype result))))

(defn- checkpoint-completed?
  "Check if the result indicates checkpoint completion."
  [result]
  (when-let [text (:result result)]
    (str/includes? (str/upper-case text) "CHECKPOINT_COMPLETE")))

(defn- extract-blocked-reason
  "Extract the blocked reason from the result if present."
  [result]
  (when-let [text (:result result)]
    (when-let [[_ reason] (re-find #"CHECKPOINT_BLOCKED:\s*(.*)" text)]
      reason)))

(defn- auto-commit-checkpoint!
  "Create a git commit as a rollback point after successful checkpoint.
   Fails silently if not a git repo or nothing to commit."
  [project-path checkpoint-num checkpoint-desc]
  (try
    (let [msg (str "Lisa: Checkpoint " checkpoint-num " - " checkpoint-desc)]
      ;; Stage all changes and commit
      (p/shell {:dir project-path :out :string :err :string :continue true}
               "git" "add" "-A")
      (p/shell {:dir project-path :out :string :err :string :continue true}
               "git" "commit" "-m" msg "--no-verify"))
    (catch Exception _
      ;; Silently ignore - not a git repo or nothing to commit
      nil)))

(defn run-loop!
  "Run the Lisa Loop orchestrator.

   Options:
   - :max-iterations - Maximum iterations (default: 20)
   - :allowed-tools - Tools to allow (default: standard set)
   - :on-iteration - Callback (fn [iteration result]) for each iteration
   - :on-complete - Callback (fn [final-plan total-cost]) when done"
  [project-path & [{:keys [on-iteration on-complete]
                    :as opts}]]
  (let [config (merge default-config opts)
        log-dir (ensure-log-dir! project-path config)]

    (loop [iteration 1
           total-cost 0.0]
      (let [current-plan (plan/parse-plan project-path)]

        (cond
          ;; No plan found
          (nil? current-plan)
          {:status :error
           :error "No LISA_PLAN.md found"
           :iterations iteration
           :total-cost total-cost}

          ;; All checkpoints complete
          (plan/all-complete? current-plan)
          (let [result {:status :complete
                        :iterations (dec iteration)
                        :total-cost total-cost}]
            (when on-complete (on-complete current-plan total-cost))
            result)

          ;; Max iterations reached
          (> iteration (:max-iterations config))
          {:status :max-iterations
           :iterations iteration
           :total-cost total-cost}

          ;; Run iteration
          :else
          (let [checkpoint (plan/current-checkpoint current-plan)
                signs (read-signs project-path)
                prompt (build-iteration-prompt current-plan checkpoint signs)
                log-file (str (fs/path log-dir (str "iter-" iteration ".json")))

                _ (println (str "[Lisa] Iteration " iteration ": Checkpoint " (:number checkpoint)
                                " - " (:description checkpoint)))

                proc (spawn-claude-iteration! project-path prompt config log-file)
                _exit-code (wait-for-process proc (:poll-interval-ms config))
                result (parse-iteration-result log-file)
                iteration-cost (or (:total_cost_usd result) 0.0)]

            ;; Call iteration callback if provided
            (when on-iteration
              (on-iteration iteration result))

            (cond
              ;; Iteration failed
              (not (iteration-succeeded? result))
              (do
                (println (str "[Lisa] Iteration " iteration " failed"))
                (when-let [blocked (extract-blocked-reason result)]
                  (append-sign! project-path iteration blocked "Needs resolution"))
                (recur (inc iteration) (+ total-cost iteration-cost)))

              ;; Checkpoint completed
              (checkpoint-completed? result)
              (do
                (println (str "[Lisa] Checkpoint " (:number checkpoint) " complete"))
                ;; Auto-commit as rollback point
                (auto-commit-checkpoint! project-path (:number checkpoint) (:description checkpoint))
                (recur (inc iteration) (+ total-cost iteration-cost)))

              ;; Iteration ran but checkpoint not marked complete
              :else
              (do
                (println (str "[Lisa] Iteration " iteration " completed, continuing..."))
                (recur (inc iteration) (+ total-cost iteration-cost))))))))))

(defn generate-plan!
  "Generate a LISA_PLAN.md by asking Claude to analyze the task and create checkpoints."
  [project-path task-description & [{:keys [max-checkpoints] :or {max-checkpoints 10}}]]
  (let [log-dir (ensure-log-dir! project-path default-config)
        log-file (str (fs/path log-dir "planning.json"))

        prompt (str "# Lisa Loop Planning\n\n"
                    "Analyze this task and create a LISA_PLAN.md with checkpoints.\n\n"
                    "## Task\n" task-description "\n\n"
                    "## Instructions\n\n"
                    "1. Break the task into " max-checkpoints " or fewer checkpoints\n"
                    "2. Each checkpoint should be independently verifiable\n"
                    "3. Include acceptance criteria that can be validated via REPL\n"
                    "4. Create the file LISA_PLAN.md in the project root\n"
                    "5. Output 'PLAN_CREATED' when done\n\n"
                    "Use the standard format:\n"
                    "### N. [PENDING] Description\n"
                    "- File: path/to/file.clj\n"
                    "- Acceptance: (some-fn arg) => expected\n")

        proc (spawn-claude-iteration! project-path prompt default-config log-file)
        _exit-code (wait-for-process proc (:poll-interval-ms default-config))
        result (parse-iteration-result log-file)]

    (if (and (iteration-succeeded? result)
             (plan/plan-exists? project-path))
      {:status :success
       :plan (plan/parse-plan project-path)
       :cost (:total_cost_usd result)}
      {:status :error
       :error (or (:result result) "Failed to generate plan")
       :cost (:total_cost_usd result)})))

(defn -main
  "Entry point for running the orchestrator from command line.
   Usage: bb -m forj.lisa.orchestrator <project-path> [max-iterations]"
  [& args]
  (let [project-path (or (first args) ".")
        max-iterations (if (second args)
                         (parse-long (second args))
                         20)]
    (println "[Lisa] Starting orchestrator")
    (println "[Lisa] Project:" project-path)
    (println "[Lisa] Max iterations:" max-iterations)

    (let [result (run-loop! project-path {:max-iterations max-iterations
                                          :on-iteration (fn [i _r]
                                                          (println (str "[Lisa] Iteration " i " complete")))
                                          :on-complete (fn [_plan cost]
                                                         (println (str "[Lisa] All checkpoints complete! Total cost: $" cost)))})]
      (println "[Lisa] Final status:" (:status result))
      (println "[Lisa] Total iterations:" (:iterations result))
      (System/exit (if (= :complete (:status result)) 0 1)))))

(comment
  ;; Test expressions

  ;; Generate a plan
  (generate-plan! "/tmp/test-project"
                  "Build a simple REST API with user CRUD operations")

  ;; Run the loop
  (run-loop! "/tmp/test-project"
             {:max-iterations 5
              :on-iteration (fn [i r] (println "Iteration" i "cost:" (:total_cost_usd r)))
              :on-complete (fn [_plan c] (println "Done! Total cost:" c))})
  )
