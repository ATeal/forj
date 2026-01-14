(ns forj.hooks.stop
  "Stop hook for Lisa Loop autonomous development.

   Intercepts session exits and decides whether to continue the loop.
   When active, runs validate_changed_files between iterations and
   injects REPL feedback into Claude's context."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [forj.hooks.loop-state :as state]
            [forj.logging :as log]
            [forj.mcp.tools :as tools]))

(defn- check-completion-promise
  "Check if the transcript contains the completion promise."
  [transcript-path completion-promise]
  (when (and transcript-path (fs/exists? transcript-path))
    (try
      (let [content (slurp transcript-path)]
        (str/includes? content (str "<promise>" completion-promise "</promise>")))
      (catch Exception _ false))))

(defn- format-validation-results
  "Format validation results for injection into Claude's context."
  [results]
  (if (:all-passed results)
    (str "All validations passed.\n"
         "Summary: " (:summary results))
    (str "Validation issues found:\n"
         "Summary: " (:summary results) "\n\n"
         "Details:\n"
         (->> (:results results)
              (map (fn [{:keys [file reload-result comment-block-result]}]
                     (str "  " file ":\n"
                          (when reload-result
                            (if (:success reload-result)
                              "    ✓ Namespace reloaded\n"
                              (str "    ✗ Reload error: " (:error reload-result) "\n")))
                          (when comment-block-result
                            (if (:success comment-block-result)
                              (str "    ✓ Comment block: " (count (:results comment-block-result)) " forms evaluated\n")
                              (str "    ✗ Comment block error: " (:error comment-block-result) "\n"))))))
              (str/join "\n")))))

(defn- run-validation
  "Run validate_changed_files and return formatted results."
  []
  (try
    (let [results (tools/validate-changed-files {})]
      {:success true
       :formatted (format-validation-results results)
       :raw results})
    (catch Exception e
      {:success false
       :formatted (str "Validation failed: " (.getMessage e))
       :raw nil})))

(defn- build-continuation-reason
  "Build the reason string for continuing the loop."
  [loop-state validation]
  (let [iteration (inc (:iteration loop-state))
        max-iter (:max-iterations loop-state)
        prompt (:prompt loop-state)
        completion-promise (:completion-promise loop-state)]
    (str "═══════════════════════════════════════════════════════════════\n"
         "LISA LOOP - Iteration " iteration "/" max-iter "\n"
         "═══════════════════════════════════════════════════════════════\n\n"
         "## REPL Validation Results\n\n"
         (:formatted validation) "\n\n"
         "## Original Task\n\n"
         prompt "\n\n"
         "## Instructions\n\n"
         "Continue working on the task. Use REPL-driven development:\n"
         "1. Write code with (comment ...) blocks for testing\n"
         "2. Use reload_namespace after file changes\n"
         "3. Use eval_comment_block to verify behavior\n"
         "4. When complete, output: <promise>" completion-promise "</promise>\n\n"
         "═══════════════════════════════════════════════════════════════")))

(defn -main
  "Entry point for Stop hook.

   When Lisa Loop is active:
   - Checks for completion promise in transcript
   - Runs validate_changed_files for REPL feedback
   - Continues loop with validation results injected

   Returns JSON: {} to allow stop, or {decision: 'block', reason: '...'} to continue."
  [& _args]
  (let [input (try
                (json/parse-string (slurp *in*) true)
                (catch Exception _ {}))
        loop-state (state/read-state)]

    (log/info "stop-hook" "Invoked" {:active? (boolean loop-state)
                                     :iteration (:iteration loop-state)})

    (if-not (:active? loop-state)
      ;; No active loop - allow stop
      (do
        (log/info "stop-hook" "No active loop, allowing stop")
        (println (json/generate-string {})))

      ;; Active loop - check completion or continue
      (let [transcript-path (:transcript_path input)
            has-promise? (check-completion-promise
                          transcript-path
                          (:completion-promise loop-state))
            iteration (:iteration loop-state)
            max-iter (:max-iterations loop-state)]

        (cond
          ;; Completion promise found
          has-promise?
          (do
            (log/info "stop-hook" "Completion promise found, ending loop"
                      {:iterations iteration})
            (state/clear-state!)
            (println (json/generate-string {})))

          ;; Max iterations reached
          (>= iteration max-iter)
          (do
            (log/info "stop-hook" "Max iterations reached, ending loop"
                      {:iterations iteration :max max-iter})
            (state/clear-state!)
            ;; Still allow stop but log it
            (println (json/generate-string {})))

          ;; Continue the loop
          :else
          (let [validation (run-validation)
                _ (state/increment-iteration! loop-state (:raw validation))
                reason (build-continuation-reason loop-state validation)]
            (log/info "stop-hook" "Continuing loop"
                      {:iteration (inc iteration)
                       :validation-passed (:success validation)})
            (println (json/generate-string
                      {:decision "block"
                       :reason reason}))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
