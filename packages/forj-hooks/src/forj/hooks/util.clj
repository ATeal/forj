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
