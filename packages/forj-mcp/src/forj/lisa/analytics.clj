(ns forj.lisa.analytics
  "Analytics for Lisa Loop iterations - tool usage and REPL compliance."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn extract-tool-calls
  "Parse a stream-json log file and extract tool calls.
   Returns a seq of {:name <tool-name> :input <tool-input>} maps.

   The log file is JSONL format where each line is a JSON object.
   Tool calls appear in 'assistant' type messages with content
   containing 'tool_use' objects."
  [log-file]
  (when (and (fs/exists? log-file)
             (pos? (fs/size log-file)))
    (let [lines (-> log-file slurp (str/split #"\n"))]
      (->> lines
           (filter seq)
           (mapcat (fn [line]
                     (try
                       (let [entry (json/parse-string line true)]
                         (when (= "assistant" (:type entry))
                           (let [content (get-in entry [:message :content])]
                             (->> content
                                  (filter #(= "tool_use" (:type %)))
                                  (map (fn [tool-use]
                                         {:name (:name tool-use)
                                          :input (:input tool-use)}))))))
                       (catch Exception _
                         nil))))
           (remove nil?)))))

(def ^:private repl-tools
  "Set of forj MCP tools that indicate proper REPL-driven development."
  #{"mcp__forj__repl_eval"
    "mcp__forj__reload_namespace"
    "mcp__forj__eval_at"
    "mcp__forj__eval_comment_block"
    "mcp__forj__doc_symbol"
    "mcp__forj__validate_changed_files"
    "mcp__forj__run_tests"
    "mcp__forj__repl_snapshot"})

(def ^:private anti-pattern-commands
  "Bash commands that indicate bypassing REPL workflow."
  [#"bb\s+-e"
   #"bb\s+-cp"
   #"clj\s+-e"
   #"clj\s+-M.*-e"
   #"lein\s+run"
   #"lein\s+repl"
   #"echo.*\|.*bb"
   #"echo.*\|.*clj"])

(defn- bash-anti-pattern?
  "Check if a Bash tool call contains an anti-pattern command."
  [tool-call]
  (when (= "Bash" (:name tool-call))
    (let [command (get-in tool-call [:input :command] "")]
      (some #(re-find % command) anti-pattern-commands))))

(defn score-repl-compliance
  "Score an iteration's REPL compliance based on tool calls.

   Takes a seq of tool calls (from extract-tool-calls) and returns:
   {:score :excellent|:good|:fair|:poor
    :used-repl-tools [...]  ; forj MCP tools used
    :anti-patterns [...]}   ; detected anti-patterns

   Scoring criteria:
   - :excellent - Uses REPL tools, no anti-patterns
   - :good - Uses some REPL tools, minor anti-patterns
   - :fair - Limited REPL usage or notable anti-patterns
   - :poor - No REPL tools or significant anti-patterns"
  [tool-calls]
  (let [tool-calls (or tool-calls [])
        used-repl-tools (->> tool-calls
                             (map :name)
                             (filter repl-tools)
                             distinct
                             vec)
        bash-anti-patterns (->> tool-calls
                                (filter bash-anti-pattern?)
                                (map #(get-in % [:input :command]))
                                vec)
        repl-tool-count (count used-repl-tools)
        anti-pattern-count (count bash-anti-patterns)
        score (cond
                ;; Excellent: Uses REPL tools and no anti-patterns
                (and (>= repl-tool-count 2) (zero? anti-pattern-count))
                :excellent

                ;; Good: Uses at least one REPL tool, few anti-patterns
                (and (pos? repl-tool-count) (<= anti-pattern-count 1))
                :good

                ;; Fair: Some REPL usage OR moderate anti-patterns
                (or (pos? repl-tool-count) (<= anti-pattern-count 2))
                :fair

                ;; Poor: No REPL tools and/or many anti-patterns
                :else
                :poor)]
    {:score score
     :used-repl-tools used-repl-tools
     :anti-patterns bash-anti-patterns}))

(comment
  ;; Test extract-tool-calls
  (extract-tool-calls ".forj/logs/lisa/parallel-error-wrapper-2.json")
  (extract-tool-calls ".forj/logs/lisa/parallel-extract-tool-calls-1.json")

  ;; Count tool calls
  (count (extract-tool-calls ".forj/logs/lisa/parallel-extract-tool-calls-1.json"))

  ;; Group by tool name
  (->> (extract-tool-calls ".forj/logs/lisa/parallel-extract-tool-calls-1.json")
       (group-by :name)
       (map (fn [[k v]] [k (count v)]))
       (into {}))

  ;; Test score-repl-compliance
  (score-repl-compliance [{:name "mcp__forj__reload_namespace"}
                          {:name "mcp__forj__eval_comment_block"}])
  ;; => {:score :excellent, :used-repl-tools [...], :anti-patterns []}

  (score-repl-compliance [{:name "Bash" :input {:command "bb -e '(+ 1 2)'"}}])
  ;; => {:score :fair, :used-repl-tools [], :anti-patterns ["bb -e '(+ 1 2)'"]}

  (score-repl-compliance [])
  ;; => {:score :fair, :used-repl-tools [], :anti-patterns []}

  ;; Score a real iteration
  (->> (extract-tool-calls ".forj/logs/lisa/parallel-extract-tool-calls-1.json")
       score-repl-compliance)
  )
