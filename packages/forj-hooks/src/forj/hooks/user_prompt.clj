(ns forj.hooks.user-prompt
  "UserPromptSubmit hook to ensure REPL-first workflow is followed."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [forj.logging :as log]))

(defn is-clojure-project?
  "Check if the current directory is a Clojure project."
  [dir]
  (or (fs/exists? (fs/path dir "deps.edn"))
      (fs/exists? (fs/path dir "bb.edn"))
      (fs/exists? (fs/path dir "shadow-cljs.edn"))
      (fs/exists? (fs/path dir "project.clj"))))

(defn -main
  "Entry point for UserPromptSubmit hook.

   Injects a reminder about REPL-first workflow when working in
   Clojure projects. This helps ensure Claude uses REPL evaluation
   rather than writing code blindly."
  [& _args]
  (let [project-dir (or (System/getenv "CLAUDE_PROJECT_DIR") ".")
        cwd (System/getProperty "user.dir")]
    (log/info "user-prompt" "Hook invoked" {:project-dir project-dir :cwd cwd})
    (when (is-clojure-project? project-dir)
      (log/info "user-prompt" "Injecting REPL reminder")
      (println (json/generate-string
                {:hookSpecificOutput
                 {:additionalContext
                  (str "Reminder: This is a Clojure project. "
                       "Prefer REPL evaluation for rapid feedback. "
                       "Use forj MCP tools: repl_eval, discover_repls.")}})))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
