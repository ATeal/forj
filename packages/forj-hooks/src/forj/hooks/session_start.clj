(ns forj.hooks.session-start
  "SessionStart hook for Clojure project detection and context injection."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [clojure.string :as str]
            [forj.logging :as log]))

(defn safe-parse-edn
  "Safely parse an EDN file, returning nil on error."
  [path]
  (when (fs/exists? path)
    (try
      (edn/read-string (slurp (str path)))
      (catch Exception _ nil))))

(defn discover-repls
  "Find running nREPL servers."
  []
  (try
    (let [result (p/shell {:out :string :err :string :continue true}
                          "clj-nrepl-eval" "--discover-ports")]
      (when (zero? (:exit result))
        (str/trim (:out result))))
    (catch Exception _ nil)))

(defn analyze-project
  "Analyze project configuration files."
  [dir]
  (let [bb-edn (safe-parse-edn (fs/path dir "bb.edn"))
        deps-edn (safe-parse-edn (fs/path dir "deps.edn"))
        shadow-edn (safe-parse-edn (fs/path dir "shadow-cljs.edn"))]
    {:project-types (cond-> #{}
                      bb-edn (conj :babashka)
                      deps-edn (conj :clojure)
                      shadow-edn (conj :clojurescript))
     :bb-tasks (some-> bb-edn :tasks keys vec)
     :deps-aliases (some-> deps-edn :aliases keys vec)
     :shadow-builds (some-> shadow-edn :builds keys vec)}))

(defn format-context
  "Format project analysis as context string."
  [{:keys [project-types bb-tasks deps-aliases shadow-builds]} repls]
  (str "CLOJURE PROJECT DETECTED\n"
       "Project types: " (str/join ", " (map name project-types)) "\n"
       (when (seq bb-tasks)
         (str "BB tasks: " (str/join ", " (map name bb-tasks)) "\n"))
       (when (seq deps-aliases)
         (str "Deps aliases: " (str/join ", " (map name deps-aliases)) "\n"))
       (when (seq shadow-builds)
         (str "Shadow builds: " (str/join ", " (map name shadow-builds)) "\n"))
       "\n"
       "Running REPLs:\n"
       (or repls "  None detected - start one with `bb dev` or `bb repl`")
       "\n\n"
       "Use the forj MCP server tools for REPL evaluation:\n"
       "- repl_eval: Evaluate Clojure code\n"
       "- discover_repls: Find running nREPL servers\n"
       "- analyze_project: Get project information"))

(defn is-clojure-project?
  "Check if the current directory is a Clojure project."
  [dir]
  (or (fs/exists? (fs/path dir "deps.edn"))
      (fs/exists? (fs/path dir "bb.edn"))
      (fs/exists? (fs/path dir "shadow-cljs.edn"))
      (fs/exists? (fs/path dir "project.clj"))))

(defn -main
  "Entry point for SessionStart hook."
  [& _args]
  (log/debug "session-start" "Hook triggered")
  (let [project-dir (or (System/getenv "CLAUDE_PROJECT_DIR") ".")]
    (if (is-clojure-project? project-dir)
      (try
        (let [analysis (analyze-project project-dir)
              repls (discover-repls)
              context (format-context analysis repls)]
          (log/info "session-start" "Clojure project detected"
                    {:types (:project-types analysis)
                     :has-repls (some? repls)})
          (println (json/generate-string
                    {:hookSpecificOutput
                     {:additionalContext context}})))
        (catch Exception e
          (log/exception "session-start" "Hook failed" e)))
      (log/debug "session-start" "Not a Clojure project"))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
