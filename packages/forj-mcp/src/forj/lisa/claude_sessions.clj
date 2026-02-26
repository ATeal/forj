(ns forj.lisa.claude-sessions
  "Read and parse Claude Code session logs from ~/.claude/projects/."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defn claude-projects-dir
  "Return the path to Claude's projects directory.
   This is ~/.claude/projects/ where session logs are stored."
  []
  (fs/path (System/getProperty "user.home") ".claude" "projects"))

(defn encode-project-path
  "Encode a project path for use in Claude's directory naming.
   Replaces / with - to match Claude's encoding scheme.
   Example: /home/user/projects/foo -> -home-user-projects-foo"
  [path]
  (-> (str path)
      (str/replace #"^/" "-")
      (str/replace "/" "-")))

(defn session-log-path
  "Get the path to a Claude session log file.

   Arguments:
   - session-id: UUID string of the session
   - project-path: (optional) Absolute path to the project directory.
                   If not provided, uses current working directory."
  ([session-id]
   (session-log-path session-id (System/getProperty "user.dir")))
  ([session-id project-path]
   (let [encoded-path (encode-project-path project-path)
         projects-dir (claude-projects-dir)]
     (fs/path projects-dir encoded-path (str session-id ".jsonl")))))

(defn read-session-jsonl
  "Read a Claude session JSONL file and return parsed entries.

   Arguments:
   - session-path: Path to the session .jsonl file

   Returns a lazy seq of parsed JSON maps, or nil if file doesn't exist."
  [session-path]
  (when (fs/exists? session-path)
    (let [content (slurp (str session-path))
          lines (str/split content #"\n")]
      (->> lines
           (filter seq)
           (map (fn [line]
                  (try
                    (json/parse-string line true)
                    (catch Exception _
                      nil))))
           (filter some?)))))

(defn extract-tool-calls
  "Extract tool call information from session entries.

   Arguments:
   - entries: Seq of parsed session entries (from read-session-jsonl)

   Returns a seq of maps with:
   - :name - tool name (e.g., \"Read\", \"mcp__forj__repl_eval\")
   - :input - tool input parameters (map)
   - :id - tool_use id"
  [entries]
  (->> entries
       (filter #(= "assistant" (:type %)))
       (mapcat (fn [entry]
                 (let [content (get-in entry [:message :content])]
                   (when (sequential? content)
                     (->> content
                          (filter #(= "tool_use" (:type %)))
                          (map (fn [tool-use]
                                 {:name (:name tool-use)
                                  :input (:input tool-use)
                                  :id (:id tool-use)})))))))
       (filter some?)))

(defn tool-call-counts
  "Count tool calls by name.

   Arguments:
   - tool-calls: Seq of tool call maps (from extract-tool-calls)

   Returns a map of {tool-name count} sorted by count descending."
  [tool-calls]
  (->> tool-calls
       (map :name)
       frequencies
       (sort-by val >)
       (into (array-map))))

(defn session-tool-summary
  "Get a summary of tool usage for a session.

   Arguments:
   - session-id: UUID string of the session
   - project-path: (optional) Project directory path

   Returns:
   {:session-id \"...\",
    :exists? true/false,
    :tool-counts {\"Read\" 5, \"Edit\" 3, ...},
    :total-calls 15,
    :entry-count 100}"
  ([session-id]
   (session-tool-summary session-id (System/getProperty "user.dir")))
  ([session-id project-path]
   (let [path (session-log-path session-id project-path)]
     (if (fs/exists? path)
       (let [entries (read-session-jsonl path)
             tool-calls (extract-tool-calls entries)]
         {:session-id session-id
          :exists? true
          :path (str path)
          :tool-counts (tool-call-counts tool-calls)
          :total-calls (count tool-calls)
          :entry-count (count entries)})
       {:session-id session-id
        :exists? false
        :path (str path)}))))

(defn extract-transcript
  "Extract conversation transcript from session entries.

   Arguments:
   - entries: Seq of parsed session entries (from read-session-jsonl)

   Returns a seq of simplified conversation turns with:
   - :type - 'user', 'assistant', or 'tool_result'
   - :content - For user/assistant: text content; for tool_result: result summary
   - :tool_calls - For assistant messages: list of tool calls made
   - :timestamp - When available"
  [entries]
  (->> entries
       (map (fn [entry]
              (let [msg-type (:type entry)
                    message (:message entry)
                    content (:content message)]
                (case msg-type
                  "user"
                  (let [text-content (if (string? content)
                                       content
                                       (->> content
                                            (filter #(= "text" (:type %)))
                                            (map :text)
                                            (str/join "\n")))]
                    {:type "user"
                     :content (if (> (count text-content) 500)
                                (str (subs text-content 0 500) "...")
                                text-content)})

                  "assistant"
                  (let [text-parts (->> content
                                        (filter #(= "text" (:type %)))
                                        (map :text))
                        tool-uses (->> content
                                       (filter #(= "tool_use" (:type %)))
                                       (map (fn [tu]
                                              {:tool (:name tu)
                                               :id (:id tu)
                                               :input-summary (let [input (:input tu)]
                                                                (cond
                                                                  (nil? input) nil
                                                                  (map? input)
                                                                  (let [s (pr-str input)]
                                                                    (if (> (count s) 200)
                                                                      (str (subs s 0 200) "...")
                                                                      s))
                                                                  :else (str input)))})))]
                    {:type "assistant"
                     :content (when (seq text-parts)
                                (let [text (str/join "\n" text-parts)]
                                  (if (> (count text) 500)
                                    (str (subs text 0 500) "...")
                                    text)))
                     :tool_calls (when (seq tool-uses) tool-uses)})

                  ;; Skip other types (like tool_result which are paired with tool_use)
                  nil))))
       (filter some?)))

(defn session-transcript
  "Get the conversation transcript for a session.

   Arguments:
   - session-id: UUID string of the session
   - project-path: (optional) Project directory path

   Returns:
   {:session-id \"...\",
    :exists? true/false,
    :transcript [{:type \"user\", :content \"...\"}, ...],
    :turn-count 10}"
  ([session-id]
   (session-transcript session-id (System/getProperty "user.dir")))
  ([session-id project-path]
   (let [path (session-log-path session-id project-path)]
     (if (fs/exists? path)
       (let [entries (read-session-jsonl path)
             transcript (extract-transcript entries)]
         {:session-id session-id
          :exists? true
          :path (str path)
          :transcript (vec transcript)
          :turn-count (count transcript)})
       {:session-id session-id
        :exists? false
        :path (str path)}))))

(defn decode-project-path
  "Best-effort decode of a Claude project directory name back to a filesystem path.
   Reverses encode-project-path by replacing leading - with / and remaining - with /.
   Note: lossy for paths that originally contained hyphens."
  [encoded]
  (when encoded
    (-> (str encoded)
        (str/replace-first #"^-" "/")
        (str/replace "-" "/"))))

(defn- parse-first-timestamp
  "Read up to n lines from a JSONL file and return the first timestamp found."
  [path n]
  (try
    (with-open [rdr (clojure.java.io/reader (str path))]
      (->> (line-seq rdr)
           (take n)
           (keep (fn [line]
                   (try
                     (let [parsed (json/parse-string line true)]
                       (:timestamp parsed))
                     (catch Exception _ nil))))
           first))
    (catch Exception _ nil)))

(defn- iso->epoch-ms
  "Convert an ISO-8601 timestamp string to epoch milliseconds."
  [s]
  (when s
    (try
      (.toEpochMilli (Instant/parse s))
      (catch Exception _ nil))))

(defn list-sessions
  "Scan ~/.claude/projects/ for session JSONL files.
   Returns a vec of session maps sorted by :updated desc.

   Each map contains:
   - :id - session UUID (filename without .jsonl)
   - :directory - encoded project directory name
   - :project-path - best-effort decoded filesystem path
   - :created - epoch ms from first timestamp in file (or file mtime)
   - :updated - epoch ms from file last-modified time
   - :size-bytes - file size in bytes"
  []
  (let [projects-dir (claude-projects-dir)]
    (when (fs/exists? projects-dir)
      (->> (fs/list-dir projects-dir)
           (filter fs/directory?)
           (mapcat (fn [project-dir]
                     (let [dir-name (str (fs/file-name project-dir))]
                       (->> (fs/glob project-dir "*.jsonl")
                            ;; Skip files inside subagents/ subdirectories
                            (remove (fn [f]
                                      (let [rel (str (fs/relativize project-dir f))]
                                        (str/starts-with? rel "subagents/"))))
                            (map (fn [f]
                                   (let [fname (str (fs/file-name f))
                                         session-id (str/replace fname #"\.jsonl$" "")
                                         mtime-ms (.toMillis (fs/last-modified-time f))
                                         created-ts (parse-first-timestamp f 10)
                                         created-ms (or (iso->epoch-ms created-ts) mtime-ms)]
                                     {:id session-id
                                      :directory dir-name
                                      :project-path (decode-project-path dir-name)
                                      :created created-ms
                                      :updated mtime-ms
                                      :size-bytes (fs/size f)})))))))
           (sort-by :updated >)
           vec))))

(comment
  ;; Test path encoding
  (encode-project-path "/home/user/Projects/github/my-project")
  ;; => "-home-user-Projects-github-my-project"

  ;; Get session log path
  (str (session-log-path "fdf605bc-e601-41c7-89be-0c24bfeebb04"))
  ;; => "/home/user/.claude/projects/-home-user-Projects-github-my-project/fdf605bc-e601-41c7-89be-0c24bfeebb04.jsonl"

  ;; Read a session
  (def entries (read-session-jsonl
                (session-log-path "fdf605bc-e601-41c7-89be-0c24bfeebb04")))
  (count entries)

  ;; Extract tool calls
  (def tool-calls (extract-tool-calls entries))
  (take 5 tool-calls)

  ;; Count tool calls
  (tool-call-counts tool-calls)

  ;; Full summary
  (session-tool-summary "fdf605bc-e601-41c7-89be-0c24bfeebb04")

  ;; Decode project path
  (decode-project-path "-home-arteal-Projects-github-forj")
  ;; => "/home/arteal/Projects/github/forj"

  ;; List all sessions
  (let [sessions (list-sessions)]
    {:count (count sessions)
     :first (first sessions)
     :last (last sessions)})

  ;; Check sessions are sorted by updated desc
  (let [sessions (list-sessions)]
    (= (map :updated sessions)
       (reverse (sort (map :updated sessions)))))
  )
