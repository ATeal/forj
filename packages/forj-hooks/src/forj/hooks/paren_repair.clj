(ns forj.hooks.paren-repair
  "Wrapper around clj-paren-repair-claude-hook with graceful error handling.

   If the underlying tool fails (bad deps, missing tool, etc.), we log a
   friendly message instead of spewing stack traces. The edit still proceeds."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private clojure-extensions
  "File extensions that should be processed by paren repair.
   Note: .cljd (ClojureDart) not included - underlying tool doesn't support it."
  #{".clj" ".cljs" ".cljc" ".edn" ".bb"})

(defn- clojure-file?
  "Check if the file path has a Clojure-family extension."
  [file-path]
  (when file-path
    (some #(str/ends-with? (str/lower-case file-path) %) clojure-extensions)))

(defn- extract-file-path
  "Extract file_path from hook input JSON."
  [input]
  (try
    (let [data (json/parse-string input true)]
      (get-in data [:tool_input :file_path]))
    (catch Exception _
      ;; If we can't parse, assume it's a Clojure file to be safe
      nil)))

(defn- tool-available?
  "Check if clj-paren-repair-claude-hook is on PATH."
  []
  (try
    (let [result (p/shell {:out :string :err :string :continue true}
                          "which" "clj-paren-repair-claude-hook")]
      (zero? (:exit result)))
    (catch Exception _ false)))

(defn- run-paren-repair
  "Run the underlying paren repair tool. Returns {:success true/false :output ...}"
  [input]
  (try
    (let [result (p/shell {:in input
                           :out :string
                           :err :string
                           :continue true}
                          "clj-paren-repair-claude-hook")]
      (if (zero? (:exit result))
        {:success true :output (:out result)}
        {:success false
         :error (str/trim (or (:err result) (:out result)))}))
    (catch Exception e
      {:success false :error (.getMessage e)})))

(defn- friendly-error-message
  "Convert cryptic errors to helpful messages."
  [error]
  (cond
    (str/includes? error "Could not find artifact")
    "Paren repair skipped: project has dependency resolution issues"

    (str/includes? error "Could not locate")
    "Paren repair skipped: missing namespace (check project deps)"

    (str/includes? error "FileNotFoundException")
    "Paren repair skipped: file not found"

    (str/includes? error "override built-in")
    ;; This is just a warning, not fatal
    nil

    :else
    (str "Paren repair skipped: " (first (str/split-lines error)))))

(defn -main
  "Hook entry point. Reads JSON from stdin, runs paren repair, outputs result."
  [& _args]
  (let [input (slurp *in*)
        file-path (extract-file-path input)]
    (cond
      ;; Not a Clojure file - skip silently
      (and file-path (not (clojure-file? file-path)))
      (print input)

      ;; Tool not installed
      (not (tool-available?))
      (do
        (binding [*out* *err*]
          (println "[forj] clj-paren-repair-claude-hook not found, skipping paren repair"))
        (print input))

      ;; Run paren repair
      :else
      (let [result (run-paren-repair input)]
        (if (:success result)
          (print (:output result))
          (do
            (when-let [msg (friendly-error-message (:error result))]
              (binding [*out* *err*]
                (println (str "[forj] " msg))))
            (print input)))))
    (flush)))

(comment
  ;; Test expressions
  (tool-available?)

  (friendly-error-message "Could not find artifact honeysql:honeysql:jar:2.6.1147")
  ;; => "Paren repair skipped: project has dependency resolution issues"

  (friendly-error-message "WARNING: task(s) 'repl' override built-in")
  ;; => nil (just a warning, ignore)
  )
