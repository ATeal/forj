(ns forj.lisa.claude-sessions
  "Read and parse Claude Code session logs from ~/.claude/projects/."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

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

(comment
  ;; Test path encoding
  (encode-project-path "/home/arteal/Projects/github/forj")
  ;; => "-home-arteal-Projects-github-forj"

  ;; Get session log path
  (str (session-log-path "fdf605bc-e601-41c7-89be-0c24bfeebb04"))
  ;; => "/home/arteal/.claude/projects/-home-arteal-Projects-github-forj/fdf605bc-e601-41c7-89be-0c24bfeebb04.jsonl"

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
  )
