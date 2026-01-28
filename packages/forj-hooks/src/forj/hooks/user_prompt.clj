(ns forj.hooks.user-prompt
  "UserPromptSubmit hook to ensure REPL-first workflow is followed."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [forj.hooks.util :as util]
            [forj.logging :as log]))

(defn- detect-lisa-loop?
  "Detect if the prompt suggests a Lisa loop context.
   Returns true if Lisa loop patterns are detected."
  [prompt-lower]
  (or (str/includes? prompt-lower "lisa loop")
      (str/includes? prompt-lower "lisa-loop")
      (str/includes? prompt-lower "repl-driven development")
      (str/includes? prompt-lower "eval_comment_block")
      (str/includes? prompt-lower "validate_changed_files")
      (str/includes? prompt-lower "<promise>")
      (str/includes? prompt-lower "iteration")
      (str/includes? prompt-lower "iterate until")
      (str/includes? prompt-lower "loop until")
      (str/includes? prompt-lower "keep trying")
      (str/includes? prompt-lower "autonomous")))

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

(defn -main
  "Entry point for UserPromptSubmit hook.

   Injects a reminder about REPL-first workflow when working in
   Clojure projects. Provides enhanced guidance when Lisa loop
   patterns are detected."
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
      (let [lisa-loop? (detect-lisa-loop? prompt-lower)
            guidance (if lisa-loop?
                       lisa-loop-guidance
                       standard-reminder)]
        (log/info "user-prompt" "Injecting guidance" {:lisa-loop? lisa-loop?})
        (println (json/generate-string
                  {:hookSpecificOutput
                   {:hookEventName "UserPromptSubmit"
                    :additionalContext guidance}}))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
