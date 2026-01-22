(ns forj.lisa.orchestrator
  "Lisa Loop orchestrator - spawns fresh Claude instances for each iteration.

   The orchestrator:
   1. Reads LISA_PLAN.edn (or .md) to find current checkpoint
   2. Spawns a Claude instance with focused prompt
   3. Waits for completion
   4. Reads output JSON for success/failure
   5. Repeats until all checkpoints done or max iterations reached"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [forj.lisa.analytics :as analytics]
            [forj.lisa.plan :as plan]
            [forj.lisa.plan-edn :as plan-edn]))

(def default-config
  {:max-iterations 20
   :max-parallel 3  ;; Max concurrent checkpoints (EDN format only)
   :verbose false   ;; Use stream-json for full tool call logs
   :allowed-tools "Bash,Edit,Read,Write,Glob,Grep,mcp__forj__*,mcp__claude-in-chrome__*"
   :log-dir ".forj/logs/lisa"
   :poll-interval-ms 5000
   ;; Timeout settings (in milliseconds)
   ;; nil = disabled, idle timeout is the primary stuck detector
   :iteration-timeout-ms nil             ;; Disabled by default (use --iteration-timeout to enable)
   :idle-timeout-ms (* 5 60 1000)        ;; 5 minutes with no file activity
   :max-checkpoint-failures 3            ;; Skip checkpoint after N consecutive failures
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
        ;; Support both EDN (:id) and markdown (:number) formats
        cp-id (or (:number checkpoint) (:id checkpoint))
        cp-desc (:description checkpoint)
        cp-file (:file checkpoint)
        cp-acceptance (:acceptance checkpoint)
        cp-validation (:validation checkpoint)
        cp-gates (:gates checkpoint)
        cp-deps (:depends-on checkpoint)
        is-ui? (ui-checkpoint? cp-desc)
        ;; Detect if using EDN format (has :id keyword)
        is-edn? (keyword? (:id checkpoint))
        plan-file (if is-edn? "LISA_PLAN.edn" "LISA_PLAN.md")]
    (str/join "\n\n"
              (filter some?
                      [(str "# Lisa Loop: " plan-title)
                       ""
                       "You are working on a focused checkpoint. Complete this ONE task, then exit."
                       ""
                       (str "## Current Checkpoint: " cp-id)
                       (str "**Task:** " cp-desc)
                       (when cp-file (str "**File:** " cp-file))
                       (when cp-acceptance (str "**Acceptance Criteria:** " cp-acceptance))
                       (when (seq cp-gates) (str "**Gates:** " (str/join " | " cp-gates)))
                       (when (seq cp-deps) (str "**Depends on:** " (str/join ", " (map name cp-deps))))
                       (when cp-validation (str "**Validation:** " cp-validation))
                       ""
                       "## REQUIRED Workflow - DO NOT SKIP"
                       ""
                       "**CRITICAL**: You MUST follow this REPL-driven workflow. Skipping steps wastes iterations."
                       ""
                       "### Step 1: Understand Current State"
                       (str "- Read " plan-file " to understand the full context")
                       "- Use `repl_eval` to query current REPL state if needed"
                       "- Read relevant source files to understand existing code"
                       ""
                       "### Step 2: Implement Changes"
                       "- Write code using Edit/Write tools"
                       (when cp-file (str "- Primary file: " cp-file))
                       ""
                       "### Step 3: REPL Validation - DO NOT SKIP"
                       "**After writing code, you MUST validate via REPL before marking complete:**"
                       ""
                       "1. `reload_namespace` - Reload changed namespaces to pick up your edits"
                       "2. `eval_comment_block` - Run examples in (comment ...) blocks to verify behavior"
                       "3. `repl_eval` - Test specific expressions to confirm correctness"
                       ""
                       "**DO NOT** mark checkpoint complete based only on:"
                       "- Reading the code and thinking it looks right"
                       "- Running tests without REPL validation first"
                       "- Assumptions about what the code does"
                       ""
                       "**DO** verify by actually evaluating code in the REPL."
                       (when is-ui?
                         (str "\n### Step 4: Visual Validation - DO NOT SKIP\n"
                              "**For UI checkpoints, you MUST take a screenshot:**\n\n"
                              "**Chrome MCP Workflow** (use these exact steps):\n"
                              "```\n"
                              "1. mcp__claude-in-chrome__tabs_context_mcp with createIfEmpty=true\n"
                              "2. mcp__claude-in-chrome__tabs_create_mcp (creates a fresh tab, returns tabId)\n"
                              "3. mcp__claude-in-chrome__navigate with url and tabId\n"
                              "4. mcp__claude-in-chrome__browser_wait_for with time=3 (wait for render)\n"
                              "5. mcp__claude-in-chrome__computer with action='screenshot' and tabId\n"
                              "```\n\n"
                              "**Alternative - Playwright MCP**:\n"
                              "```\n"
                              "1. mcp__playwright__browser_navigate to the app URL\n"
                              "2. mcp__playwright__browser_wait_for with time=3\n"
                              "3. mcp__playwright__browser_take_screenshot\n"
                              "```\n\n"
                              "Check shadow-cljs.edn :dev-http, package.json scripts, or LISA_PLAN.md for the correct URL/port.\n\n"
                              "CHECKPOINT WILL BE REJECTED if you mark complete without screenshot verification."))
                       ""
                       (str "### Step " (if is-ui? "5" "4") ": Mark Complete")
                       (str "When acceptance criteria are verified via REPL" (when is-ui? " and screenshot") ":")
                       (if is-edn?
                         (str "- Use `lisa_mark_checkpoint_done` tool with checkpoint '" (name cp-id) "'")
                         (str "- Edit " plan-file " to mark this checkpoint [DONE]"))
                       "- Output 'CHECKPOINT_COMPLETE'"
                       ""
                       "If blocked, output 'CHECKPOINT_BLOCKED: <reason>' with specific details."
                       ""
                       (when (seq signs-content)
                         (str "## Previous Learnings (Signs)\n\n" signs-content))
                       ""
                       "Focus ONLY on this checkpoint. Do not work ahead."]))))

(defn- use-edn-format?
  "Check if we should use EDN format."
  [project-path]
  (plan-edn/plan-exists? project-path))

(defn- read-signs
  "Read signs - from EDN embedded or LISA_SIGNS.md."
  [project-path]
  (if (use-edn-format? project-path)
    ;; EDN format - get recent signs and format them
    (let [plan (plan-edn/read-plan project-path)
          signs (plan-edn/recent-signs plan 5)]
      (when (seq signs)
        (str/join "\n\n"
                  (map (fn [s]
                         (str "### Sign (iteration " (:iteration s) ")\n"
                              "**Issue:** " (:issue s) "\n"
                              "**Fix:** " (:fix s)))
                       signs))))
    ;; Markdown fallback
    (let [signs-path (str (fs/path project-path "LISA_SIGNS.md"))]
      (when (fs/exists? signs-path)
        (slurp signs-path)))))

(defn- append-sign!
  "Append a sign (learning) - to EDN embedded or LISA_SIGNS.md."
  [project-path iteration issue fix]
  (if (use-edn-format? project-path)
    ;; EDN format - embedded signs
    (plan-edn/append-sign! project-path
                           {:iteration iteration
                            :issue issue
                            :fix fix
                            :severity :error})
    ;; Markdown fallback
    (let [signs-path (str (fs/path project-path "LISA_SIGNS.md"))
          timestamp (str (java.time.Instant/now))
          sign-entry (str "\n## Sign " iteration " (" timestamp ")\n"
                          "**Issue:** " issue "\n"
                          "**Fix:** " fix "\n")]
      (spit signs-path sign-entry :append true))))

;;; =============================================================================
;;; Tool Call Streaming (verbose mode)
;;; =============================================================================

(defn- tool-name->display
  "Convert a tool name to a shorter display form."
  [tool-name]
  (cond
    (str/starts-with? tool-name "mcp__forj__")
    (str "forj:" (subs tool-name 11))

    (str/starts-with? tool-name "mcp__")
    (let [parts (str/split (subs tool-name 5) #"__" 2)]
      (if (= 2 (count parts))
        (str (first parts) ":" (second parts))
        (subs tool-name 5)))

    :else tool-name))

(defn- parse-jsonl-line
  "Parse a single JSONL line, returning nil on failure."
  [line]
  (when (and line (not (str/blank? line)))
    (try
      (json/parse-string line true)
      (catch Exception _ nil))))

(defn- extract-tool-names-from-entry
  "Extract tool names from an assistant message entry."
  [entry]
  (when (= "assistant" (:type entry))
    (let [content (get-in entry [:message :content])]
      (->> content
           (filter #(= "tool_use" (:type %)))
           (map :name)))))

(defn- stream-tool-calls!
  "Stream tool calls from a log file in real-time.
   Returns a map with:
   - :stop-fn - Call this to stop streaming
   - :future - The Future object for the streaming thread

   Prints tool names as they appear in the log file.
   Automatically stops when stop-fn is called or the file stops growing."
  [log-file]
  (let [stop-atom (atom false)
        seen-tools (atom #{})
        stop-fn (fn [] (reset! stop-atom true))
        future-obj
        (future
          (try
            ;; Wait for log file to exist
            (loop [wait-count 0]
              (when (and (not @stop-atom)
                         (< wait-count 100)  ;; Max 10 seconds wait
                         (not (fs/exists? log-file)))
                (Thread/sleep 100)
                (recur (inc wait-count))))

            ;; Stream the file
            (when (and (not @stop-atom) (fs/exists? log-file))
              (with-open [rdr (io/reader log-file)]
                (loop []
                  (when-not @stop-atom
                    (if-let [line (.readLine rdr)]
                      (do
                        ;; Parse and print any new tool calls
                        (when-let [entry (parse-jsonl-line line)]
                          (when-let [tools (extract-tool-names-from-entry entry)]
                            (doseq [tool tools]
                              (when-not (@seen-tools tool)
                                (swap! seen-tools conj tool)
                                (println (str "[Lisa] 🔧 " (tool-name->display tool)))))))
                        (recur))
                      ;; No line available - wait and retry unless stopped
                      (do
                        (Thread/sleep 100)
                        (recur)))))))
            (catch Exception e
              (when-not @stop-atom
                (println (str "[Lisa] Stream error: " (.getMessage e)))))))]
    {:stop-fn stop-fn
     :future future-obj}))

;;; =============================================================================
;;; Process Spawning
;;; =============================================================================

(defn- spawn-claude-iteration!
  "Spawn a Claude instance for one iteration.
   Uses stdin to pass prompt, avoiding shell quoting issues.

   When :verbose true in config, uses stream-json format for detailed tool call logs
   and starts a real-time stream of tool calls to the console.

   Returns {:process <proc> :stream <stream-info>} where stream-info has :stop-fn to call
   when the process completes. In non-verbose mode, :stream is nil."
  [project-path prompt config log-file]
  ;; Pass prompt via stdin to avoid shell escaping issues
  ;; --dangerously-skip-permissions is REQUIRED for non-interactive mode
  ;; --mcp-config passes user config so Chrome MCP is available for visual validation
  ;; NOTE: stream-json requires --verbose flag when using -p (print mode)
  (let [verbose? (:verbose config)
        base-args ["claude" "-p"
                   "--output-format" (if verbose? "stream-json" "json")
                   "--dangerously-skip-permissions"
                   "--mcp-config" (:mcp-config config)]
        args (if verbose?
               (conj base-args "--verbose")
               base-args)
        ;; Start the stream before spawning process so it's ready to catch output
        stream (when verbose? (stream-tool-calls! log-file))
        proc (apply p/process
                    {:dir project-path
                     :in prompt
                     :out (java.io.File. log-file)
                     :err :stdout}
                    args)]
    {:process proc
     :stream stream}))

(defn- last-src-modification
  "Get the most recent modification time of source files in the project."
  [project-path]
  (try
    (let [src-files (concat
                     (fs/glob (str project-path "/src") "**/*.{clj,cljs,cljc}")
                     (fs/glob (str project-path "/test") "**/*.{clj,cljs,cljc}"))]
      (if (seq src-files)
        (->> src-files
             (map #(.toMillis (fs/last-modified-time %)))
             (apply max))
        0))
    (catch Exception _ 0)))

(defn- wait-for-process-with-timeout
  "Wait for process to complete with timeout and idle detection.
   Returns {:status :completed|:timeout|:idle, :exit-code N, :reason string}"
  [proc project-path config]
  (let [start-time (System/currentTimeMillis)
        iteration-timeout (:iteration-timeout-ms config)
        idle-timeout (:idle-timeout-ms config)
        poll-interval (:poll-interval-ms config)
        initial-file-time (last-src-modification project-path)]
    (loop [last-activity-time start-time
           last-file-time initial-file-time]
      (let [now (System/currentTimeMillis)
            elapsed (- now start-time)
            idle-elapsed (- now last-activity-time)
            current-file-time (last-src-modification project-path)
            file-changed? (> current-file-time last-file-time)]
        (cond
          ;; Process completed normally
          (not (p/alive? proc))
          {:status :completed
           :exit-code (:exit @proc)
           :elapsed-ms elapsed}

          ;; Iteration timeout exceeded
          (and iteration-timeout (> elapsed iteration-timeout))
          (do
            (println (str "[Lisa] ⚠ Iteration timed out after " (/ elapsed 60000) " minutes"))
            (p/destroy-tree proc)
            {:status :timeout
             :exit-code -1
             :elapsed-ms elapsed
             :reason (str "Iteration exceeded " (/ iteration-timeout 60000) " minute timeout")})

          ;; Idle timeout - no file activity
          (and idle-timeout
               (> idle-elapsed idle-timeout)
               (not file-changed?))
          (do
            (println (str "[Lisa] ⚠ No file activity for " (/ idle-elapsed 60000) " minutes, killing iteration"))
            (p/destroy-tree proc)
            {:status :idle
             :exit-code -1
             :elapsed-ms elapsed
             :reason (str "No file activity for " (/ idle-timeout 60000) " minutes")})

          ;; Continue waiting
          :else
          (do
            (Thread/sleep poll-interval)
            (recur (if file-changed? now last-activity-time)
                   (if file-changed? current-file-time last-file-time))))))))

(defn- wait-for-process
  "Wait for process to complete, polling at intervals. Returns exit code.
   Legacy wrapper for backward compatibility."
  [proc poll-interval-ms]
  (loop []
    (if (p/alive? proc)
      (do
        (Thread/sleep poll-interval-ms)
        (recur))
      (:exit @proc))))

(defn- parse-iteration-result
  "Parse the JSON output from a Claude iteration.
   Handles both json (single object) and stream-json (JSONL) formats.

   For stream-json, finds the result message and extracts cost/token info."
  [log-file]
  (try
    (when-not (fs/exists? log-file)
      (throw (ex-info "Log file does not exist" {:file log-file})))
    (let [content (slurp log-file)]
      ;; Handle empty log files (process failed to start or crashed immediately)
      (when (str/blank? content)
        (throw (ex-info "Log file is empty - process may have failed to start" {:file log-file})))
      ;; Check if it's JSONL (stream-json) by looking for newlines between JSON objects
      (if (and (str/includes? content "\n{")
               (str/starts-with? (str/trim content) "{"))
        ;; stream-json format: parse each line, find result message
        (let [lines (str/split-lines content)
              parsed (keep (fn [line]
                             (when (not (str/blank? line))
                               (try
                                 (json/parse-string line true)
                                 (catch Exception _ nil))))
                           lines)
              ;; Find the result message (type: result)
              result-msg (first (filter #(= "result" (:type %)) parsed))
              ;; Also look for any message with cost info
              cost-msg (first (filter :total_cost_usd parsed))]
          (if result-msg
            (merge result-msg
                   (when cost-msg
                     (select-keys cost-msg [:total_cost_usd :usage])))
            ;; Fallback: return last parsed message
            (or (last parsed)
                {:is_error true :error "No result message found in stream"})))
        ;; Standard json format: single object
        (json/parse-string content true)))
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

(defn- compliance->sign-fix
  "Generate a fix recommendation based on compliance issues."
  [{:keys [used-repl-tools anti-patterns]}]
  (cond
    (and (empty? used-repl-tools) (seq anti-patterns))
    (str "Use forj MCP tools (reload_namespace, eval_comment_block, repl_eval) instead of Bash commands like: "
         (str/join ", " (take 2 (map #(first (str/split % #"\s+" 3)) anti-patterns))))

    (empty? used-repl-tools)
    "After writing code, use reload_namespace to pick up changes, then eval_comment_block to verify behavior"

    (seq anti-patterns)
    "Replace Bash REPL commands (bb -e, clj -e) with forj MCP tools for better feedback"

    :else
    "Follow the REPL-driven workflow: reload_namespace → eval_comment_block → repl_eval"))

(defn- log-iteration-analytics
  "Parse tool calls from iteration log and display summary with compliance score.
   Only called when verbose=true (stream-json format).
   Returns the compliance data for use in sign generation."
  [log-file iteration]
  (let [tool-calls (analytics/extract-tool-calls log-file)]
    ;; Print the formatted summary table with tool usage breakdown
    (analytics/print-iteration-summary tool-calls iteration)))

(defn- maybe-append-compliance-sign!
  "Append a sign when REPL compliance is poor.
   This teaches future iterations to use REPL-driven workflow."
  [project-path iteration compliance checkpoint-desc]
  (when (= :poor (:score compliance))
    (let [issue (str "Poor REPL compliance in iteration " iteration
                     (when checkpoint-desc (str " (" checkpoint-desc ")"))
                     " - no forj MCP tools used for validation")
          fix (compliance->sign-fix compliance)]
      (append-sign! project-path iteration issue fix)
      (println (str "[Lisa] ⚠ Sign appended: Poor REPL compliance - " fix)))))

(defn- count-checkpoint-failures
  "Count consecutive failures for a checkpoint from signs."
  [project-path checkpoint-id]
  (if (plan-edn/plan-exists? project-path)
    (let [plan (plan-edn/read-plan project-path)
          signs (:signs plan [])]
      (->> signs
           (filter #(= (:checkpoint %) checkpoint-id))
           (filter #(= (:severity %) :error))
           count))
    0))

(defn- should-skip-checkpoint?
  "Check if a checkpoint should be skipped due to repeated failures."
  [project-path checkpoint-id max-failures]
  (>= (count-checkpoint-failures project-path checkpoint-id) max-failures))

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

(defn- cleanup-previous-run!
  "Clean up logs from previous runs to prevent stale output confusion."
  [project-path config]
  (let [log-dir (str (fs/path project-path (:log-dir config)))]
    ;; Clear iteration logs from previous runs
    (doseq [f (fs/glob log-dir "iter-*.json")]
      (fs/delete f))
    ;; Clear orchestrator log if it exists
    (let [orch-log (str (fs/path log-dir "orchestrator.log"))]
      (when (fs/exists? orch-log)
        (spit orch-log "")))))

(defn- read-plan
  "Read plan from EDN (preferred) or markdown format."
  [project-path]
  (cond
    (plan-edn/plan-exists? project-path)
    {:format :edn
     :plan (plan-edn/read-plan project-path)}

    (plan/plan-exists? project-path)
    {:format :markdown
     :plan (plan/parse-plan project-path)}

    :else nil))

(defn- plan-all-complete?
  "Check if all checkpoints are complete (format-aware)."
  [{:keys [format plan]}]
  (case format
    :edn (plan-edn/all-complete? plan)
    :markdown (plan/all-complete? plan)))

(defn- plan-current-checkpoint
  "Get current checkpoint (format-aware)."
  [{:keys [format plan]}]
  (case format
    :edn (plan-edn/current-checkpoint plan)
    :markdown (plan/current-checkpoint plan)))

;;; =============================================================================
;;; Parallel Execution (EDN format only)
;;; =============================================================================

(defn- mark-checkpoint-in-progress!
  "Mark a checkpoint as in-progress in the plan file."
  [project-path checkpoint-id]
  (when-let [plan (plan-edn/read-plan project-path)]
    (-> plan
        (plan-edn/update-checkpoint checkpoint-id
                                    {:status :in-progress
                                     :started (str (java.time.Instant/now))})
        (->> (plan-edn/write-plan! project-path)))))

(defn- spawn-checkpoint-process!
  "Spawn a Claude process for a specific checkpoint. Returns process info map.
   Also marks the checkpoint as in-progress in the plan file."
  [project-path checkpoint config log-dir iteration-base]
  (let [cp-id (or (:id checkpoint) (:number checkpoint))
        cp-name (name cp-id)
        log-file (str (fs/path log-dir (str "parallel-" cp-name "-" iteration-base ".json")))
        ;; Mark as in-progress BEFORE spawning
        _ (mark-checkpoint-in-progress! project-path cp-id)
        plan (plan-edn/read-plan project-path)
        signs (read-signs project-path)
        prompt (build-iteration-prompt plan checkpoint signs)
        {:keys [process stream]} (spawn-claude-iteration! project-path prompt config log-file)]
    {:checkpoint-id cp-id
     :checkpoint checkpoint
     :process process
     :stream stream
     :log-file log-file
     :started-at (System/currentTimeMillis)}))

(defn- stop-stream!
  "Stop the tool call stream if present."
  [stream]
  (when-let [stop-fn (:stop-fn stream)]
    (stop-fn)))

(defn- check-process-complete
  "Check if a process has completed or should be killed due to idle timeout.
   Returns updated info with :completed true if done or killed.
   Stops the tool call stream when process completes."
  [proc-info project-path config]
  (let [proc (:process proc-info)
        idle-timeout (:idle-timeout-ms config)
        now (System/currentTimeMillis)
        started-at (:started-at proc-info)
        last-activity (or (:last-activity proc-info) started-at)
        current-file-time (last-src-modification project-path)
        prev-file-time (or (:last-file-time proc-info) current-file-time)
        file-changed? (> current-file-time prev-file-time)
        new-last-activity (if file-changed? now last-activity)
        idle-elapsed (- now new-last-activity)]
    (cond
      ;; Process completed normally
      (not (p/alive? proc))
      (do
        ;; Stop the tool call stream
        (stop-stream! (:stream proc-info))
        (let [result (parse-iteration-result (:log-file proc-info))]
          (assoc proc-info
                 :completed true
                 :result result
                 :succeeded (iteration-succeeded? result)
                 :checkpoint-complete (checkpoint-completed? result))))

      ;; Idle timeout - no file activity for too long
      (and idle-timeout (> idle-elapsed idle-timeout))
      (do
        (println (str "[Lisa] ⚠ Checkpoint " (name (:checkpoint-id proc-info))
                      " idle for " (int (/ idle-elapsed 60000)) " minutes, killing"))
        ;; Stop the tool call stream
        (stop-stream! (:stream proc-info))
        (p/destroy-tree proc)
        (assoc proc-info
               :completed true
               :result {:result "Idle timeout - no file activity"}
               :succeeded false
               :checkpoint-complete false
               :idle-killed true))

      ;; Still running, update activity tracking
      :else
      (assoc proc-info
             :last-activity new-last-activity
             :last-file-time current-file-time))))

(defn- process-completed-checkpoints!
  "Handle completed processes - mark checkpoints done, auto-commit, log.
   When config has :verbose true, also logs analytics and appends signs for poor compliance."
  [project-path completed-procs config]
  (doseq [{:keys [checkpoint-id checkpoint checkpoint-complete succeeded result idle-killed log-file]} completed-procs]
    ;; Log analytics for verbose mode (completed processes only, not idle-killed)
    ;; and auto-append sign for poor compliance
    (when (and (:verbose config) (not idle-killed) log-file)
      (println (str "[Lisa] Analytics for checkpoint " (name checkpoint-id) ":"))
      (let [compliance (log-iteration-analytics log-file 0)]
        (maybe-append-compliance-sign! project-path 0 compliance (:description checkpoint))))
    (let [cp-display (or (:number checkpoint) checkpoint-id)]
      (cond
        ;; Idle-killed - reset to pending for retry
        idle-killed
        (do
          (println (str "[Lisa] ↻ Checkpoint " cp-display " reset to pending after idle timeout"))
          ;; Reset checkpoint status to pending so it can be retried
          (plan-edn/update-checkpoint (plan-edn/read-plan project-path) checkpoint-id
                                      {:status :pending})
          (-> (plan-edn/read-plan project-path)
              (plan-edn/update-checkpoint checkpoint-id {:status :pending})
              (->> (plan-edn/write-plan! project-path)))
          ;; Record sign so future iterations know this checkpoint tends to get stuck
          (append-sign! project-path 0
                        (str "Checkpoint " cp-display " timed out - may need smaller scope or different approach")
                        "Consider breaking into smaller tasks"))

        ;; Checkpoint completed successfully
        (and succeeded checkpoint-complete)
        (do
          (println (str "[Lisa] ✓ Checkpoint " cp-display " complete"))
          ;; Mark done in plan (orchestrator manages plan state)
          (plan-edn/mark-checkpoint-done! project-path checkpoint-id)
          ;; Auto-commit as rollback point
          (auto-commit-checkpoint! project-path cp-display (:description checkpoint)))

        ;; Process succeeded but checkpoint not marked complete - reset to pending for next iteration
        succeeded
        (do
          (println (str "[Lisa] → Checkpoint " cp-display " iteration done, needs more work"))
          ;; Reset to pending so it can continue in next iteration
          (-> (plan-edn/read-plan project-path)
              (plan-edn/update-checkpoint checkpoint-id {:status :pending})
              (->> (plan-edn/write-plan! project-path))))

        ;; Process failed - reset to pending for retry
        :else
        (do
          (println (str "[Lisa] ✗ Checkpoint " cp-display " iteration failed, resetting to pending"))
          ;; Reset checkpoint status to pending so it can be retried
          (-> (plan-edn/read-plan project-path)
              (plan-edn/update-checkpoint checkpoint-id {:status :pending})
              (->> (plan-edn/write-plan! project-path)))
          (when-let [blocked (extract-blocked-reason result)]
            (append-sign! project-path 0 blocked "Needs resolution")))))))

(defn- sum-costs
  "Sum costs from completed process results."
  [completed-procs]
  (reduce (fn [acc {:keys [result]}]
            (let [usage (:usage result)
                  cost (or (:total_cost_usd result) 0.0)
                  input (+ (or (:input_tokens usage) 0)
                           (or (:cache_read_input_tokens usage) 0)
                           (or (:cache_creation_input_tokens usage) 0))
                  output (or (:output_tokens usage) 0)]
              {:cost (+ (:cost acc) cost)
               :input (+ (:input acc) input)
               :output (+ (:output acc) output)}))
          {:cost 0.0 :input 0 :output 0}
          completed-procs))

(defn- reset-orphaned-checkpoints!
  "Reset any in-progress checkpoints that have no active process.
   This handles cases where a previous run crashed or was killed."
  [project-path active-proc-ids]
  (when-let [plan (plan-edn/read-plan project-path)]
    (let [in-progress (filter #(= :in-progress (:status %)) (:checkpoints plan))
          orphaned (remove #(contains? active-proc-ids (:id %)) in-progress)]
      (when (seq orphaned)
        (println (str "[Lisa] Resetting " (count orphaned) " orphaned checkpoint(s): "
                      (str/join ", " (map #(name (:id %)) orphaned))))
        (doseq [cp orphaned]
          (-> (plan-edn/read-plan project-path)
              (plan-edn/update-checkpoint (:id cp) {:status :pending})
              (->> (plan-edn/write-plan! project-path))))))))

(defn run-loop-parallel!
  "Run Lisa Loop with parallel checkpoint execution (EDN format only).

   Spawns multiple Claude processes for independent checkpoints.
   Checkpoints with satisfied dependencies run concurrently.

   Options:
   - :max-iterations - Maximum total iterations (default: 20)
   - :max-parallel - Max concurrent checkpoints (default: 3)
   - :on-checkpoint-complete - Callback (fn [checkpoint]) per completion
   - :on-complete - Callback (fn [final-plan total-cost]) when all done"
  [project-path & [{:keys [on-checkpoint-complete on-complete]
                    :as opts}]]
  (let [config (merge default-config opts)
        log-dir (ensure-log-dir! project-path config)
        _ (cleanup-previous-run! project-path config)
        ;; Reset any orphaned in-progress checkpoints from previous crashed runs
        _ (reset-orphaned-checkpoints! project-path #{})
        max-parallel (:max-parallel config)]

    (println (str "[Lisa] Starting parallel execution (max " max-parallel " concurrent)"))

    (loop [iteration 1
           active-procs []
           total-cost 0.0
           total-input 0
           total-output 0]

      ;; Check for completion or limits
      (let [plan (plan-edn/read-plan project-path)]
        (cond
          ;; All done
          (plan-edn/all-complete? plan)
          (let [result {:status :complete
                        :iterations iteration
                        :total-cost total-cost
                        :total-input-tokens total-input
                        :total-output-tokens total-output}]
            (println "[Lisa] All checkpoints complete!")
            (when on-complete (on-complete plan total-cost))
            result)

          ;; Max iterations
          (> iteration (:max-iterations config))
          {:status :max-iterations
           :iterations iteration
           :total-cost total-cost
           :total-input-tokens total-input
           :total-output-tokens total-output}

          :else
          ;; Main parallel loop logic
          (let [;; Check which active processes have completed (or idle-killed)
                checked-procs (mapv #(check-process-complete % project-path config) active-procs)
                completed (filter :completed checked-procs)
                still-active (remove :completed checked-procs)

                ;; Process completions
                _ (when (seq completed)
                    (process-completed-checkpoints! project-path completed config)
                    ;; Only call callback for checkpoints that actually succeeded
                    (doseq [{:keys [checkpoint succeeded checkpoint-complete]} completed]
                      (when (and on-checkpoint-complete succeeded checkpoint-complete)
                        (on-checkpoint-complete checkpoint))))

                ;; Sum up costs from completed
                costs (sum-costs completed)

                ;; Re-read plan to get updated ready checkpoints
                ready (when (< (count still-active) max-parallel)
                        (let [fresh-plan (plan-edn/read-plan project-path)
                              ready-cps (plan-edn/ready-checkpoints fresh-plan)
                              active-ids (set (map :checkpoint-id still-active))]
                          ;; Filter out already active checkpoints
                          (remove #(active-ids (:id %)) ready-cps)))

                ;; How many new processes to spawn
                slots-available (- max-parallel (count still-active))
                to-spawn (take slots-available ready)

                ;; Spawn new processes
                new-procs (when (seq to-spawn)
                            (println (str "[Lisa] Spawning " (count to-spawn)
                                          " parallel checkpoint(s): "
                                          (str/join ", " (map #(name (:id %)) to-spawn))))
                            (mapv #(spawn-checkpoint-process! project-path % config log-dir iteration)
                                  to-spawn))

                ;; Combine active processes
                all-active (into (vec still-active) new-procs)]

            ;; If nothing active and nothing to spawn, check for orphaned in-progress
            (if (and (empty? all-active) (empty? to-spawn))
              (let [;; Check if there are orphaned in-progress checkpoints
                    fresh-plan (plan-edn/read-plan project-path)
                    in-progress (filter #(= :in-progress (:status %)) (:checkpoints fresh-plan))]
                (if (seq in-progress)
                  ;; Reset orphaned checkpoints and continue
                  (do
                    (println "[Lisa] Detected orphaned in-progress checkpoints, resetting...")
                    (reset-orphaned-checkpoints! project-path #{})
                    (Thread/sleep (:poll-interval-ms config))
                    (recur iteration all-active
                           (+ total-cost (:cost costs))
                           (+ total-input (:input costs))
                           (+ total-output (:output costs))))
                  ;; Truly stuck - no pending or in-progress checkpoints
                  (do
                    (println "[Lisa] No active processes and no ready checkpoints - stuck")
                    {:status :stuck
                     :iterations iteration
                     :total-cost (+ total-cost (:cost costs))
                     :total-input-tokens (+ total-input (:input costs))
                     :total-output-tokens (+ total-output (:output costs))})))

              ;; Continue loop - poll interval
              (do
                (Thread/sleep (:poll-interval-ms config))
                (recur (if (seq completed) (inc iteration) iteration)
                       all-active
                       (+ total-cost (:cost costs))
                       (+ total-input (:input costs))
                       (+ total-output (:output costs)))))))))))

(defn run-loop!
  "Run the Lisa Loop orchestrator.

   Options:
   - :max-iterations - Maximum iterations (default: 20)
   - :max-parallel - Max concurrent checkpoints for parallel mode (default: 3)
   - :parallel - Enable parallel execution for EDN plans (default: false)
   - :allowed-tools - Tools to allow (default: standard set)
   - :on-iteration - Callback (fn [iteration result]) for each iteration
   - :on-checkpoint-complete - Callback (fn [checkpoint]) for parallel mode
   - :on-complete - Callback (fn [final-plan total-cost]) when done"
  [project-path & [{:keys [on-iteration on-complete _on-checkpoint-complete parallel]
                    :as opts}]]
  ;; Dispatch to parallel mode if requested and using EDN format
  (if (and parallel (use-edn-format? project-path))
    (run-loop-parallel! project-path opts)
    ;; Sequential mode (default)
    (let [config (merge default-config opts)
          log-dir (ensure-log-dir! project-path config)
          _ (cleanup-previous-run! project-path config)]

      (loop [iteration 1
             total-cost 0.0
             total-input-tokens 0
             total-output-tokens 0]
        (let [plan-data (read-plan project-path)
            current-plan (:plan plan-data)]

        (cond
          ;; No plan found
          (nil? plan-data)
          {:status :error
           :error "No LISA_PLAN.edn or LISA_PLAN.md found"
           :iterations iteration
           :total-cost total-cost
           :total-input-tokens total-input-tokens
           :total-output-tokens total-output-tokens}

          ;; All checkpoints complete
          (plan-all-complete? plan-data)
          (let [result {:status :complete
                        :iterations (dec iteration)
                        :total-cost total-cost
                        :total-input-tokens total-input-tokens
                        :total-output-tokens total-output-tokens}]
            (when on-complete (on-complete current-plan total-cost))
            result)

          ;; Max iterations reached
          (> iteration (:max-iterations config))
          {:status :max-iterations
           :iterations iteration
           :total-cost total-cost
           :total-input-tokens total-input-tokens
           :total-output-tokens total-output-tokens}

          ;; Run iteration
          :else
          (let [checkpoint (plan-current-checkpoint plan-data)
                ;; For EDN, use :id if no :number; add number for display
                cp-id (:id checkpoint)
                cp-display (or (:number checkpoint) cp-id)
                max-failures (:max-checkpoint-failures config)]

            ;; Check if checkpoint should be skipped due to repeated failures
            (if (and cp-id max-failures (should-skip-checkpoint? project-path cp-id max-failures))
              (do
                (println (str "[Lisa] ⚠ Skipping checkpoint " cp-display " after " max-failures " consecutive failures"))
                (append-sign! project-path iteration
                              (str "Checkpoint " cp-display " skipped after " max-failures " failures")
                              "Consider breaking into smaller tasks or manual intervention")
                ;; Mark as failed and continue to next
                (when (plan-edn/plan-exists? project-path)
                  (plan-edn/mark-checkpoint-failed! project-path cp-id "Too many failures"))
                (recur (inc iteration) total-cost total-input-tokens total-output-tokens))

              ;; Normal iteration
              (let [signs (read-signs project-path)
                    prompt (build-iteration-prompt current-plan checkpoint signs)
                    log-file (str (fs/path log-dir (str "iter-" iteration ".json")))

                    _ (println (str "[Lisa] Iteration " iteration ": Checkpoint " cp-display
                                    " - " (:description checkpoint)))

                    {:keys [process stream]} (spawn-claude-iteration! project-path prompt config log-file)
                    wait-result (wait-for-process-with-timeout process project-path config)
                    wait-status (:status wait-result)
                    ;; Stop the stream when process completes
                    _ (stop-stream! stream)]

                ;; Handle timeout/idle cases
                (if (#{:timeout :idle} wait-status)
                  (do
                    (append-sign! project-path iteration
                                  (:reason wait-result)
                                  "Consider breaking checkpoint into smaller tasks")
                    (recur (inc iteration) total-cost total-input-tokens total-output-tokens))

                  ;; Normal completion - parse result
                  (let [result (parse-iteration-result log-file)
                        iteration-cost (or (:total_cost_usd result) 0.0)
                        usage (:usage result)
                        iter-input (+ (or (:input_tokens usage) 0)
                                      (or (:cache_read_input_tokens usage) 0)
                                      (or (:cache_creation_input_tokens usage) 0))
                        iter-output (or (:output_tokens usage) 0)
                        _ (println (str "[Lisa] Tokens: " iter-input " in / " iter-output " out, Cost: $" (format "%.2f" iteration-cost)))
                        ;; Log analytics when verbose (stream-json provides tool call data)
                        ;; and auto-append sign for poor compliance
                        compliance (when (:verbose config)
                                     (log-iteration-analytics log-file iteration))
                        _ (when compliance
                            (maybe-append-compliance-sign! project-path iteration compliance (:description checkpoint)))]

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
                        (recur (inc iteration)
                               (+ total-cost iteration-cost)
                               (+ total-input-tokens iter-input)
                               (+ total-output-tokens iter-output)))

                      ;; Checkpoint completed
                      (checkpoint-completed? result)
                      (do
                        (println (str "[Lisa] Checkpoint " cp-display " complete"))
                        ;; Auto-commit as rollback point
                        (auto-commit-checkpoint! project-path cp-display (:description checkpoint))
                        (recur (inc iteration)
                               (+ total-cost iteration-cost)
                               (+ total-input-tokens iter-input)
                               (+ total-output-tokens iter-output)))

                      ;; Iteration ran but checkpoint not marked complete
                      :else
                      (do
                        (println (str "[Lisa] Iteration " iteration " completed, continuing..."))
                        (recur (inc iteration)
                               (+ total-cost iteration-cost)
                               (+ total-input-tokens iter-input)
                               (+ total-output-tokens iter-output)))))))))))))))

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

        {:keys [process stream]} (spawn-claude-iteration! project-path prompt default-config log-file)
        _exit-code (wait-for-process process (:poll-interval-ms default-config))
        _ (stop-stream! stream)
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
   Usage: bb -m forj.lisa.orchestrator <project-path> [options]
   Options:
     --max-iterations N     Maximum iterations (default: 20)
     --sequential           Disable parallel execution (parallel is default for EDN plans)
     --max-parallel N       Max concurrent checkpoints (default: 3)
     --verbose              Use stream-json for detailed tool call logs
     --iteration-timeout N  Timeout per iteration in seconds (disabled by default)
     --idle-timeout N       Kill if no file activity for N seconds (default: 300 = 5 min)
     --no-timeout           Disable all timeouts"
  [& args]
  (let [;; Parse args
        {:keys [project-path max-iterations parallel max-parallel verbose
                iteration-timeout idle-timeout no-timeout]}
        (loop [args args
               opts {:project-path "." :max-iterations 20 :parallel true :max-parallel 3
                     :verbose false :iteration-timeout nil :idle-timeout 300 :no-timeout false}]
          (if (empty? args)
            opts
            (let [[arg & more] args]
              (cond
                (= "--sequential" arg)
                (recur more (assoc opts :parallel false))

                (= "--verbose" arg)
                (recur more (assoc opts :verbose true))

                (= "--no-timeout" arg)
                (recur more (assoc opts :no-timeout true))

                (= "--max-iterations" arg)
                (recur (rest more) (assoc opts :max-iterations (parse-long (first more))))

                (= "--max-parallel" arg)
                (recur (rest more) (assoc opts :max-parallel (parse-long (first more))))

                (= "--iteration-timeout" arg)
                (recur (rest more) (assoc opts :iteration-timeout (parse-long (first more))))

                (= "--idle-timeout" arg)
                (recur (rest more) (assoc opts :idle-timeout (parse-long (first more))))

                (not (str/starts-with? arg "--"))
                (recur more (assoc opts :project-path arg))

                :else
                (recur more opts)))))]

    (println "[Lisa] Starting orchestrator")
    (println "[Lisa] Project:" project-path)
    (println "[Lisa] Max iterations:" max-iterations)
    (when parallel
      (println "[Lisa] Parallel mode enabled (max" max-parallel "concurrent)"))
    (when verbose
      (println "[Lisa] Verbose mode enabled (stream-json output)"))
    (if no-timeout
      (println "[Lisa] Timeouts disabled")
      (println "[Lisa] Timeouts: idle=" idle-timeout "s"
               (if iteration-timeout (str ", iteration=" iteration-timeout "s") ", iteration=disabled")))

    (let [result (run-loop! project-path
                            {:max-iterations max-iterations
                             :parallel parallel
                             :max-parallel max-parallel
                             :verbose verbose
                             ;; Convert seconds to milliseconds, nil disables timeout
                             :iteration-timeout-ms (when (and (not no-timeout) iteration-timeout)
                                                     (* iteration-timeout 1000))
                             :idle-timeout-ms (when-not no-timeout (* idle-timeout 1000))
                             :on-iteration (fn [i _r]
                                             (println (str "[Lisa] Iteration " i " complete")))
                             :on-checkpoint-complete (fn [cp]
                                                       (println (str "[Lisa] Checkpoint " (:id cp) " complete")))
                             :on-complete (fn [_plan cost]
                                            (println (str "[Lisa] All checkpoints complete! Total cost: $" cost)))})]
      (println "[Lisa] Final status:" (:status result))
      (println "[Lisa] Total iterations:" (:iterations result))
      (println "[Lisa] Total tokens:" (:total-input-tokens result) "in /" (:total-output-tokens result) "out")
      (println "[Lisa] Total cost: $" (format "%.2f" (or (:total-cost result) 0.0)))
      ;; Terminal bell on completion
      (print "\u0007")
      (flush)
      (System/exit (if (= :complete (:status result)) 0 1)))))

(comment
  ;; Test expressions

  ;; Test tool-name->display
  (tool-name->display "mcp__forj__reload_namespace")
  ;; => "forj:reload_namespace"

  (tool-name->display "mcp__playwright__browser_navigate")
  ;; => "playwright:browser_navigate"

  (tool-name->display "Read")
  ;; => "Read"

  ;; Test parse-jsonl-line
  (parse-jsonl-line "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\"}]}}")
  ;; => {:type "assistant", :message {:content [{:type "tool_use", :name "Read"}]}}

  ;; Test extract-tool-names-from-entry
  (extract-tool-names-from-entry {:type "assistant" :message {:content [{:type "tool_use" :name "Read"} {:type "text" :text "hello"}]}})
  ;; => ("Read")

  ;; Test stream-tool-calls! (creates a streaming reader)
  ;; This starts a background thread that tails the log file
  (def test-stream (stream-tool-calls! "/tmp/test-stream.jsonl"))
  ;; Stop the stream:
  ((:stop-fn test-stream))

  ;; Generate a plan
  (generate-plan! "/tmp/test-project"
                  "Build a simple REST API with user CRUD operations")

  ;; Run the loop
  (run-loop! "/tmp/test-project"
             {:max-iterations 5
              :on-iteration (fn [i r] (println "Iteration" i "cost:" (:total_cost_usd r)))
              :on-complete (fn [_plan c] (println "Done! Total cost:" c))})

  ;; Test compliance->sign-fix - generates fix guidance based on compliance issues
  (compliance->sign-fix {:score :poor :used-repl-tools [] :anti-patterns ["bb -e '(+ 1 2)'"]})
  ;; => "Use forj MCP tools (reload_namespace, eval_comment_block, repl_eval) instead of Bash commands like: bb"

  (compliance->sign-fix {:score :poor :used-repl-tools [] :anti-patterns []})
  ;; => "After writing code, use reload_namespace to pick up changes, then eval_comment_block to verify behavior"

  ;; Test maybe-append-compliance-sign! - only appends for :poor compliance
  (maybe-append-compliance-sign! "." 5 {:score :poor :used-repl-tools [] :anti-patterns []} "Test checkpoint")
  ;; Appends sign with guidance

  (maybe-append-compliance-sign! "." 5 {:score :good :used-repl-tools ["reload_namespace"] :anti-patterns []} "Test checkpoint")
  ;; => nil (no sign appended for non-poor compliance)
  )
