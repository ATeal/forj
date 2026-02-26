(ns forj.lisa.opencode-sessions
  "Read OpenCode session data from its SQLite database."
  (:require [babashka.fs :as fs]
            [babashka.pods :as pods]
            [cheshire.core :as json]
            [clojure.string :as str]))

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

(defn- parse-part
  "Parse a raw part row, extracting fields from the JSON data column."
  [row]
  (let [data (json/parse-string (:data row) true)]
    (merge {:type     (:type data)
            :created  (:time_created row)
            :updated  (:time_updated row)}
           (case (:type data)
             "tool" {:call-id (:callID data)
                     :tool    (:tool data)
                     :input   (get-in data [:state :input])
                     :status  (get-in data [:state :status])
                     :output  (get-in data [:state :output])}
             "text" {:text (:text data)}
             "step-start" {}
             {}))))

(defn session-parts
  "Return parts for a session, ordered by time_created.
   Each part is a map with :type plus type-specific fields.
   Tool parts include :call-id :tool :input :status :output."
  [session-id]
  (mapv parse-part
        (query "SELECT id, session_id, time_created, time_updated, data
                FROM part
                WHERE session_id = ?
                ORDER BY time_created"
               session-id)))

(defn extract-tool-calls
  "Extract tool call info from session parts.
   Returns a vec of maps with :name :input :id matching the shape
   from claude-sessions/extract-tool-calls."
  [parts]
  (->> parts
       (filter #(= "tool" (:type %)))
       (mapv (fn [part]
               {:name  (:tool part)
                :input (:input part)
                :id    (:call-id part)}))))

(defn tool-call-counts
  "Count tool calls by name.
   Takes tool-calls (from extract-tool-calls), returns a map of
   {tool-name count} sorted by count descending."
  [tool-calls]
  (->> tool-calls
       (map :name)
       frequencies
       (sort-by val >)
       (into (array-map))))

(defn- truncate
  "Truncate string s to max-len characters, appending '...' if truncated."
  [s max-len]
  (when s
    (if (> (count s) max-len)
      (str (subs s 0 max-len) "...")
      s)))

(defn- message-parts
  "Return parts for a given message-id, ordered by time_created.
   Each part is parsed from its JSON data column."
  [session-id message-id]
  (mapv parse-part
        (query "SELECT id, session_id, time_created, time_updated, data
                FROM part
                WHERE session_id = ? AND message_id = ?
                ORDER BY time_created"
               session-id message-id)))

(defn- messages-with-ids
  "Return raw message rows with id and parsed role for a session."
  [session-id]
  (mapv (fn [row]
          (let [data (json/parse-string (:data row) true)]
            {:id   (:id row)
             :role (:role data)}))
        (query "SELECT id, data
                FROM message
                WHERE session_id = ?
                ORDER BY time_created"
               session-id)))

(defn extract-transcript
  "Extract conversation transcript from a session.
   Returns a vec of simplified conversation turns matching the shape
   from claude-sessions/extract-transcript:
   - :type - \"user\" or \"assistant\"
   - :content - text content (truncated to 500 chars)
   - :tool_calls - for assistant messages, list of tool call summaries"
  [session-id]
  (let [msgs (messages-with-ids session-id)]
    (->> msgs
         (map (fn [{:keys [id role]}]
                (let [parts (message-parts session-id id)]
                  (case role
                    "user"
                    (let [text (->> parts
                                    (filter #(= "text" (:type %)))
                                    (map :text)
                                    (str/join "\n"))]
                      {:type "user"
                       :content (truncate text 500)})

                    "assistant"
                    (let [text-parts (->> parts
                                          (filter #(= "text" (:type %)))
                                          (map :text))
                          tool-uses (->> parts
                                         (filter #(= "tool" (:type %)))
                                         (map (fn [p]
                                                {:tool (:tool p)
                                                 :id   (:call-id p)
                                                 :input-summary
                                                 (let [input (:input p)]
                                                   (when input
                                                     (let [s (pr-str input)]
                                                       (truncate s 200))))})))]
                      {:type "assistant"
                       :content (when (seq text-parts)
                                  (truncate (str/join "\n" text-parts) 500))
                       :tool_calls (when (seq tool-uses) tool-uses)})

                    ;; Skip unknown roles
                    nil))))
         (filter some?)
         vec)))

(defn session-summary
  "Get a summary of a session including tool usage.
   Returns a map matching the shape from claude-sessions/session-tool-summary:
   - :id - session ID
   - :exists? - whether the session was found
   - :tool-counts - {tool-name count} sorted by count desc
   - :total-calls - total number of tool calls
   - :turn-count - number of conversation turns"
  [session-id]
  (let [parts (session-parts session-id)]
    (if (seq parts)
      (let [tool-calls (extract-tool-calls parts)
            transcript (extract-transcript session-id)]
        {:id          session-id
         :exists?     true
         :tool-counts (tool-call-counts tool-calls)
         :total-calls (count tool-calls)
         :turn-count  (count transcript)})
      ;; No parts found — check if session exists at all
      (let [sessions (query "SELECT id FROM session WHERE id = ?" session-id)]
        (if (seq sessions)
          {:id          session-id
           :exists?     true
           :tool-counts {}
           :total-calls 0
           :turn-count  0}
          {:id      session-id
           :exists? false})))))

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

  ;; Get parts for a session
  (session-parts sid)

  ;; Check part types
  (frequencies (map :type (session-parts sid)))

  ;; Extract tool calls
  (extract-tool-calls (session-parts sid))

  ;; Check tool call keys match claude-sessions shape
  (keys (first (extract-tool-calls (session-parts sid))))
  ;; => (:name :input :id)

  ;; Tool call counts
  (tool-call-counts (extract-tool-calls (session-parts sid)))

  ;; Extract transcript
  (extract-transcript sid)
  (count (extract-transcript sid))
  (first (extract-transcript sid))

  ;; Session summary
  (session-summary sid)
  (keys (session-summary sid))
  ;; => (:id :exists? :tool-counts :total-calls :turn-count)

  ;; Non-existent session
  (session-summary "fake-session-id")
  ;; => {:id "fake-session-id" :exists? false}
  )
