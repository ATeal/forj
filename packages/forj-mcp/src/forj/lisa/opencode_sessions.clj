(ns forj.lisa.opencode-sessions
  "Read OpenCode session data from its SQLite database."
  (:require [babashka.fs :as fs]
            [babashka.pods :as pods]
            [cheshire.core :as json]))

;; Load SQLite pod
(pods/load-pod 'org.babashka/go-sqlite3 "0.2.3")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(defn db-path
  "Return the path to OpenCode's SQLite database."
  []
  (str (fs/path (System/getProperty "user.home")
                ".local" "share" "opencode" "opencode.db")))

(defn query
  "Execute a SQL query against the OpenCode database.
   Returns a vec of maps, or nil if the DB doesn't exist."
  [sql & params]
  (let [path (db-path)]
    (when (fs/exists? path)
      (sqlite/query path (vec (cons sql params))))))

(defn- rename-session-keys
  "Rename SQL result keys to idiomatic Clojure keywords."
  [row]
  (-> row
      (assoc :project-name (:project_name row))
      (dissoc :project_name)))

(defn list-sessions
  "List all OpenCode sessions with project info.
   Returns a vec of maps with :id :title :directory :project-name
   :created :updated (timestamps as epoch millis).
   Sorted by updated descending."
  []
  (mapv rename-session-keys
        (query "SELECT s.id, s.title, s.directory,
                       p.name as project_name,
                       s.time_created as created,
                       s.time_updated as updated
                FROM session s
                JOIN project p ON s.project_id = p.id
                ORDER BY s.time_updated DESC")))

(defn- parse-message
  "Parse a raw message row, extracting fields from the JSON data column."
  [row]
  (let [data (json/parse-string (:data row) true)]
    {:role    (:role data)
     :model   (:modelID data)
     :tokens  (:tokens data)
     :cost    (:cost data)
     :created (:time_created row)
     :updated (:time_updated row)}))

(defn session-messages
  "Return messages for a session, ordered by time_created.
   Each message is a map with :role :model :tokens :cost :created :updated."
  [session-id]
  (mapv parse-message
        (query "SELECT time_created, time_updated, data
                FROM message
                WHERE session_id = ?
                ORDER BY time_created"
               session-id)))

(comment
  ;; Check DB exists
  (db-path)
  (fs/exists? (db-path))

  ;; List all sessions
  (list-sessions)

  ;; Check keys
  (keys (first (list-sessions)))
  ;; => (:id :title :directory :project-name :created :updated)

  ;; Count sessions
  (count (list-sessions))

  ;; Get messages for a session
  (def sid (:id (first (list-sessions))))
  (session-messages sid)

  ;; Check message keys
  (keys (first (session-messages sid)))
  ;; => (:role :model :tokens :cost :created :updated)

  ;; Count messages
  (count (session-messages sid))
  )
