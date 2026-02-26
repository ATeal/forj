(ns forj.lisa.sessions
  "Unified session interface across Claude CLI and OpenCode.
   Normalizes session data from both sources into a common shape."
  (:require [babashka.fs :as fs]
            [forj.lisa.claude-sessions :as claude]
            [forj.lisa.opencode-sessions :as opencode]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defn normalize-tool-name
  "Normalize a tool name to lowercase for consistent counting across sources.
   Claude CLI uses PascalCase (Read, Glob, Bash), OpenCode uses lowercase (read, glob, bash)."
  [name]
  (when name (str/lower-case name)))

(defn- normalize-tool-counts
  "Normalize tool count keys to lowercase, merging counts for same-named tools."
  [tool-counts]
  (reduce-kv (fn [m k v]
               (let [normalized (normalize-tool-name k)]
                 (update m normalized (fnil + 0) v)))
             {}
             tool-counts))

(def common-keys
  "The set of keys present in every normalized session map."
  #{:id :source :title :directory :project-name :created :updated :client-label})

(defn normalize-claude-session
  "Convert a raw claude-sessions/list-sessions map into the common session shape.
   Claude sessions have :id :directory :project-path :created :updated :size-bytes."
  [raw]
  {:id           (:id raw)
   :source       :claude-cli
   :title        (:title raw)
   :directory    (or (:project-path raw) (:directory raw))
   :project-name (when-let [pp (:project-path raw)]
                   (last (re-seq #"[^/]+" pp)))
   :created      (:created raw)
   :updated      (:updated raw)
   :client-label "Claude CLI"})

(defn normalize-opencode-session
  "Convert a raw opencode-sessions/list-sessions map into the common session shape.
   OpenCode sessions have :id :title :directory :project-name :created :updated."
  [raw]
  {:id           (:id raw)
   :source       :opencode
   :title        (:title raw)
   :directory    (:directory raw)
   :project-name (:project-name raw)
   :created      (:created raw)
   :updated      (:updated raw)
   :client-label "OpenCode"})

(defn list-recent-sessions
  "Return recent sessions from both Claude CLI and OpenCode, merged and sorted.

   Options (all optional):
   - :since    - epoch ms; only sessions updated after this time
   - :project  - substring match on :directory
   - :client   - :claude-cli or :opencode; nil for both
   - :limit    - max results (default 20)"
  ([] (list-recent-sessions {}))
  ([{:keys [since project client limit] :or {limit 20}}]
   (let [claude-sessions  (when-not (= client :opencode)
                            (try
                              (->> (claude/list-sessions)
                                   (mapv normalize-claude-session))
                              (catch Exception _ [])))
         opencode-sessions (when-not (= client :claude-cli)
                             (try
                               (->> (opencode/list-sessions)
                                    (mapv normalize-opencode-session))
                               (catch Exception _ [])))
         all (concat (or claude-sessions []) (or opencode-sessions []))]
     (cond->> all
       since   (filter #(and (:updated %) (> (:updated %) since)))
       project (filter #(and (:directory %)
                              (str/includes? (str/lower-case (str (:directory %)))
                                             (str/lower-case project))))
       true    (sort-by :updated #(compare %2 %1))
       true    (take limit)
       true    vec))))

(defn- iso->epoch-ms
  "Convert an ISO-8601 timestamp string to epoch milliseconds."
  [s]
  (when s
    (try
      (.toEpochMilli (Instant/parse s))
      (catch Exception _ nil))))

(defn- claude-session-summary
  "Build a summary for a Claude CLI session by reading entries once."
  [id directory]
  (let [project-path (or directory (System/getProperty "user.dir"))
        path (claude/session-log-path id project-path)]
    (if (fs/exists? path)
      (let [entries    (claude/read-session-jsonl path)
            tool-calls (claude/extract-tool-calls entries)
            transcript (claude/extract-transcript entries)
            timestamps (keep iso->epoch-ms (keep :timestamp entries))
            ts-vec     (vec timestamps)
            model      (->> entries
                            (filter #(= "assistant" (:type %)))
                            first
                            :message
                            :model)]
        {:id          id
         :source      :claude-cli
         :exists?     true
         :tool-counts (normalize-tool-counts (claude/tool-call-counts tool-calls))
         :total-calls (count tool-calls)
         :turn-count  (count transcript)
         :duration-ms (when (>= (count ts-vec) 2)
                        (- (peek ts-vec) (first ts-vec)))
         :cost        nil
         :model       model})
      {:id id :source :claude-cli :exists? false})))

(defn- opencode-session-summary
  "Build a summary for an OpenCode session, enriched with message data."
  [id]
  (let [summary (opencode/session-summary id)]
    (if (:exists? summary)
      (let [messages    (opencode/session-messages id)
            duration-ms (when (>= (count messages) 2)
                          (let [start (:created (first messages))
                                end   (:updated (last messages))]
                            (when (and start end (number? start) (number? end))
                              (- end start))))
            cost        (let [c (reduce + 0 (keep :cost messages))]
                          (when (pos? c) c))
            model       (->> messages
                             (filter #(= "assistant" (:role %)))
                             first
                             :model)]
        {:id          id
         :source      :opencode
         :exists?     true
         :tool-counts (normalize-tool-counts (:tool-counts summary))
         :total-calls (:total-calls summary)
         :turn-count  (:turn-count summary)
         :duration-ms duration-ms
         :cost        cost
         :model       model})
      {:id id :source :opencode :exists? false})))

(defn session-summary
  "Get a normalized summary for a session from either source.

   Takes a map with:
   - :id     - session identifier
   - :source - :claude-cli or :opencode
   - :directory - (optional, for :claude-cli) project path, defaults to cwd

   Returns:
   {:id :source :tool-counts :total-calls :turn-count :duration-ms :cost :model}"
  [{:keys [id source directory]}]
  (case source
    :claude-cli (claude-session-summary id directory)
    :opencode   (opencode-session-summary id)
    (throw (ex-info (str "Unknown session source: " source)
                    {:source source :id id}))))

(comment
  ;; Verify both normalizers produce identical key sets
  (= (set (keys (normalize-claude-session
                  {:id "abc-123"
                   :directory "-home-arteal-Projects-github-forj"
                   :project-path "/home/arteal/Projects/github/forj"
                   :created 1700000000000
                   :updated 1700001000000
                   :size-bytes 12345})))
     (set (keys (normalize-opencode-session
                  {:id "ses_xyz"
                   :title "Test session"
                   :directory "/home/arteal/Projects/github/forj"
                   :project-name "forj"
                   :created 1700000000000
                   :updated 1700001000000})))
     common-keys)
  ;; => true

  ;; Check a normalized claude session
  (normalize-claude-session
    {:id "abc-123"
     :directory "-home-arteal-Projects-github-forj"
     :project-path "/home/arteal/Projects/github/forj"
     :created 1700000000000
     :updated 1700001000000
     :size-bytes 12345})

  ;; Check a normalized opencode session
  (normalize-opencode-session
    {:id "ses_xyz"
     :title "Test session"
     :directory "/home/arteal/Projects/github/forj"
     :project-name "forj"
     :created 1700000000000
     :updated 1700001000000})

  ;; --- list-recent-sessions ---

  ;; All sessions, default limit 20
  (let [sessions (list-recent-sessions)]
    {:count (count sessions)
     :sources (frequencies (map :source sessions))
     :first (first sessions)})

  ;; Sorted by :updated desc?
  (let [sessions (list-recent-sessions)]
    (= (map :updated sessions)
       (reverse (sort (map :updated sessions)))))

  ;; Filter by client
  (count (list-recent-sessions {:client :claude-cli}))
  (count (list-recent-sessions {:client :opencode}))

  ;; Filter by project substring
  (list-recent-sessions {:project "forj" :limit 5})

  ;; Filter by since (last 24 hours)
  (list-recent-sessions {:since (- (System/currentTimeMillis) 86400000)})

  ;; Combine filters
  (list-recent-sessions {:client :claude-cli :project "forj" :limit 3})

  ;; --- session-summary ---

  ;; Summary for an opencode session
  (let [oc-session (first (list-recent-sessions {:client :opencode :limit 1}))]
    (when oc-session
      (session-summary {:id (:id oc-session) :source :opencode})))

  ;; Summary for a claude-cli session
  (let [cl-session (first (list-recent-sessions {:client :claude-cli :limit 1}))]
    (when cl-session
      (session-summary {:id (:id cl-session)
                        :source :claude-cli
                        :directory (:directory cl-session)})))

  ;; Non-existent session
  (session-summary {:id "fake-id" :source :opencode})
  ;; => {:id "fake-id" :source :opencode :exists? false}

  ;; Check normalized keys
  (let [oc-session (first (list-recent-sessions {:client :opencode :limit 1}))]
    (when oc-session
      (keys (session-summary {:id (:id oc-session) :source :opencode}))))
  ;; => (:id :source :tool-counts :total-calls :turn-count :duration-ms :cost :model)

  ;; --- normalize-tool-name ---
  (normalize-tool-name "Read")    ;; => "read"
  (normalize-tool-name "Bash")    ;; => "bash"
  (normalize-tool-name "glob")    ;; => "glob"
  (normalize-tool-name nil)       ;; => nil

  ;; --- normalize-tool-counts ---
  ;; PascalCase keys become lowercase
  (normalize-tool-counts {"Read" 5 "Bash" 3 "Glob" 2})
  ;; => {"read" 5 "bash" 3 "glob" 2}

  ;; Already lowercase stays the same
  (normalize-tool-counts {"read" 5 "bash" 3})
  ;; => {"read" 5 "bash" 3}

  ;; Verify both sources produce lowercase tool names in summaries
  (let [cl (first (list-recent-sessions {:client :claude-cli :limit 1}))
        oc (first (list-recent-sessions {:client :opencode :limit 1}))]
    {:claude-keys (when cl
                    (keys (:tool-counts (session-summary {:id (:id cl)
                                                          :source :claude-cli
                                                          :directory (:directory cl)}))))
     :opencode-keys (when oc
                      (keys (:tool-counts (session-summary {:id (:id oc)
                                                             :source :opencode}))))})
  )
