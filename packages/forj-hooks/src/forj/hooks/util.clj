(ns forj.hooks.util
  "Shared utilities for forj hooks."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn is-clojure-project?
  "Check if the given directory is a Clojure project.
   Returns true if any of deps.edn, bb.edn, shadow-cljs.edn, or project.clj exists."
  [dir]
  (or (fs/exists? (fs/path dir "deps.edn"))
      (fs/exists? (fs/path dir "bb.edn"))
      (fs/exists? (fs/path dir "shadow-cljs.edn"))
      (fs/exists? (fs/path dir "project.clj"))))

(defn discover-repls
  "Find running nREPL servers using clj-nrepl-eval.
   Returns formatted string of discovered REPLs, or nil on error."
  []
  (try
    (let [result (p/shell {:out :string :err :string :continue true}
                          "clj-nrepl-eval" "--discover-ports")]
      (when (zero? (:exit result))
        (str/trim (:out result))))
    (catch Exception _ nil)))

(defn discover-nrepl-port
  "Find an nREPL port for the given project directory.
   Checks .nrepl-port file first (standard convention), then
   falls back to clj-nrepl-eval --discover-ports."
  [project-dir]
  (or
   ;; Check .nrepl-port file (standard nREPL convention)
   (let [port-file (fs/path project-dir ".nrepl-port")]
     (when (fs/exists? port-file)
       (try (parse-long (str/trim (slurp (str port-file))))
            (catch Exception _ nil))))
   ;; Check shadow-cljs nrepl port
   (let [shadow-port (fs/path project-dir ".shadow-cljs" "nrepl.port")]
     (when (fs/exists? shadow-port)
       (try (parse-long (str/trim (slurp (str shadow-port))))
            (catch Exception _ nil))))
   ;; Fall back to discovery (finds any running REPL, picks first for this dir)
   (try
     (let [result (p/shell {:out :string :err :string :continue true :dir project-dir}
                           "clj-nrepl-eval" "--discover-ports")
           output (when (zero? (:exit result)) (:out result))]
       (when output
         (some->> (re-find #"localhost:(\d+)" output)
                  second
                  parse-long)))
     (catch Exception _ nil))))
