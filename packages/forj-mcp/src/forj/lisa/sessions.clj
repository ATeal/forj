(ns forj.lisa.sessions
  "Unified session interface across Claude CLI and OpenCode.
   Normalizes session data from both sources into a common shape."
  (:require [forj.lisa.claude-sessions :as claude]
            [forj.lisa.opencode-sessions :as opencode]
            [clojure.string :as str]))

(def common-keys
  "The set of keys present in every normalized session map."
  #{:id :source :title :directory :project-name :created :updated :client-label})

(defn normalize-claude-session
  "Convert a raw claude-sessions/list-sessions map into the common session shape.
   Claude sessions have :id :directory :project-path :created :updated :size-bytes."
  [raw]
  {:id           (:id raw)
   :source       :claude-cli
   :title        nil
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
  )
