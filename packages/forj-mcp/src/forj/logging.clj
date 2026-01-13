(ns forj.logging
  "Structured file-based logging for forj MCP server and hooks.

   Logs to ~/.forj/logs/ by default, configurable via:
   - FORJ_LOG_DIR: override log directory
   - FORJ_LOG_LEVEL: debug, info, warn, error (default: info)"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def log-levels {:debug 0 :info 1 :warn 2 :error 3})

(defn- get-log-dir []
  (or (System/getenv "FORJ_LOG_DIR")
      (str (fs/path (System/getProperty "user.home") ".forj" "logs"))))

(defn- get-log-level []
  (keyword (or (System/getenv "FORJ_LOG_LEVEL") "info")))

(defn- ensure-log-dir [log-dir]
  (when-not (fs/exists? log-dir)
    (fs/create-dirs log-dir)))

(defn- timestamp []
  (str (java.time.LocalDateTime/now)))

(defn- format-data [data]
  (when data
    (str " | " (pr-str data))))

(defn log
  "Write a log entry. Level is :debug, :info, :warn, or :error."
  [level log-name message & [data]]
  (let [current-level (get-log-level)
        level-val (get log-levels level 1)
        current-val (get log-levels current-level 1)]
    (when (>= level-val current-val)
      (try
        (let [log-dir (get-log-dir)
              log-file (str (fs/path log-dir (str log-name ".log")))
              entry (str (timestamp) " [" (str/upper-case (name level)) "] "
                         message (format-data data) "\n")]
          (ensure-log-dir log-dir)
          (spit log-file entry :append true)
          ;; Also write errors to a combined error log
          (when (#{:warn :error} level)
            (spit (str (fs/path log-dir "errors.log"))
                  (str "[" log-name "] " entry)
                  :append true)))
        (catch Exception _
          ;; Silently fail - don't crash if logging fails
          nil)))))

(defn debug [log-name msg & [data]] (log :debug log-name msg data))
(defn info [log-name msg & [data]] (log :info log-name msg data))
(defn warn [log-name msg & [data]] (log :warn log-name msg data))
(defn error [log-name msg & [data]] (log :error log-name msg data))

(defn exception
  "Log an exception with stack trace."
  [log-name msg ^Exception e]
  (error log-name msg
         {:exception (str (type e))
          :message (.getMessage e)
          :trace (take 10 (map str (.getStackTrace e)))}))
