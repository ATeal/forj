(ns forj.lisa.sessions
  "Unified session interface across Claude CLI and OpenCode.
   Normalizes session data from both sources into a common shape.")

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
  )
