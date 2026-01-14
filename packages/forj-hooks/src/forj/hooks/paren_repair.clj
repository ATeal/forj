(ns forj.hooks.paren-repair
  "Wrapper around clj-paren-repair-claude-hook with graceful error handling.

   If the underlying tool fails (bad deps, missing tool, etc.), we log a
   friendly message instead of spewing stack traces. The edit still proceeds."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

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
  (let [input (slurp *in*)]
    (if (tool-available?)
      (let [result (run-paren-repair input)]
        (if (:success result)
          ;; Success - output the repaired result
          (print (:output result))
          ;; Failure - log friendly message, pass through original
          (do
            (when-let [msg (friendly-error-message (:error result))]
              (binding [*out* *err*]
                (println (str "[forj] " msg))))
            ;; Pass through the original input unchanged
            (print input))))
      ;; Tool not installed - just pass through
      (do
        (binding [*out* *err*]
          (println "[forj] clj-paren-repair-claude-hook not found, skipping paren repair"))
        (print input)))
    (flush)))

(comment
  ;; Test expressions
  (tool-available?)

  (friendly-error-message "Could not find artifact honeysql:honeysql:jar:2.6.1147")
  ;; => "Paren repair skipped: project has dependency resolution issues"

  (friendly-error-message "WARNING: task(s) 'repl' override built-in")
  ;; => nil (just a warning, ignore)
  )
