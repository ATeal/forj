(ns forj.hooks.user-prompt
  "UserPromptSubmit hook to ensure REPL-first workflow is followed."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [forj.hooks.util :as util]
            [forj.logging :as log]))

(defn- detect-iterative-context
  "Detect if the prompt suggests an iterative/Ralph loop context.
   Returns :lisa-loop, :ralph-loop, or nil."
  [prompt-lower]
  (cond
    ;; Lisa Loop specific patterns
    (or (str/includes? prompt-lower "lisa loop")
        (str/includes? prompt-lower "lisa-loop")
        (str/includes? prompt-lower "repl-driven development")
        (str/includes? prompt-lower "eval_comment_block")
        (str/includes? prompt-lower "validate_changed_files"))
    :lisa-loop

    ;; Ralph Loop / iterative patterns
    (or (str/includes? prompt-lower "<promise>")
        (str/includes? prompt-lower "ralph")
        (str/includes? prompt-lower "iteration")
        (str/includes? prompt-lower "iterate until")
        (str/includes? prompt-lower "loop until")
        (str/includes? prompt-lower "keep trying")
        (str/includes? prompt-lower "autonomous"))
    :ralph-loop

    :else nil))

(def ^:private standard-reminder
  (str "IMPORTANT: This is a Clojure project. "
       "DO NOT use `bb -e`, `bb -cp`, or `clj -e` for code evaluation. "
       "ALWAYS use forj MCP tools instead:\n"
       "- reload_namespace: Load changed files into REPL\n"
       "- repl_eval: Evaluate expressions in REPL\n"
       "- eval_comment_block: Run all forms in a (comment ...) block\n"
       "- discover_repls: Find running nREPL servers\n"
       "If no REPL is running, start one with `bb nrepl` and track it with track_process."))

(def ^:private lisa-loop-guidance
  (str "LISA LOOP ACTIVE: Use REPL-driven validation:\n"
       "1. After writing code, use reload_namespace to pick up changes\n"
       "2. Use eval_comment_block to verify behavior BEFORE running tests\n"
       "3. Use validate_changed_files for bulk validation\n"
       "4. Only run tests (run_tests) when REPL confirms correctness\n"
       "This saves iterations by catching errors faster."))

(def ^:private ralph-loop-guidance
  (str "ITERATIVE LOOP DETECTED: Use Lisa Loop methodology for faster feedback:\n"
       "1. Write functions with (comment ...) blocks containing test expressions\n"
       "2. reload_namespace after each file change\n"
       "3. eval_comment_block to see actual return values\n"
       "4. Iterate until comment block outputs are correct\n"
       "5. Then run full tests with run_tests\n"
       "Tools: reload_namespace, eval_comment_block, validate_changed_files"))

(defn -main
  "Entry point for UserPromptSubmit hook.

   Injects a reminder about REPL-first workflow when working in
   Clojure projects. Provides enhanced guidance when iterative
   loop patterns (Ralph/Lisa) are detected."
  [& _args]
  (let [project-dir (or (System/getenv "CLAUDE_PROJECT_DIR") ".")
        cwd (System/getProperty "user.dir")
        ;; Read prompt from stdin (Claude Code passes it as JSON)
        input (try (slurp *in*) (catch Exception _ ""))
        prompt (try
                 (-> (json/parse-string input true)
                     :prompt
                     (or ""))
                 (catch Exception _ ""))
        prompt-lower (str/lower-case prompt)]
    (log/info "user-prompt" "Hook invoked" {:project-dir project-dir :cwd cwd})
    (when (util/is-clojure-project? project-dir)
      (let [context-type (detect-iterative-context prompt-lower)
            guidance (case context-type
                       :lisa-loop lisa-loop-guidance
                       :ralph-loop ralph-loop-guidance
                       standard-reminder)]
        (log/info "user-prompt" "Injecting guidance" {:context-type context-type})
        (println (json/generate-string
                  {:hookSpecificOutput
                   {:hookEventName "UserPromptSubmit"
                    :additionalContext guidance}}))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
