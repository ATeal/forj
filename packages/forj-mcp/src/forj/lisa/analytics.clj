(ns forj.lisa.analytics
  "Analytics for Lisa Loop iterations - tool usage and REPL compliance.

   Tool extraction is delegated to forj.lisa.claude-sessions.
   This namespace focuses on compliance scoring and formatting."
  (:require [clojure.string :as str]
            [forj.lisa.claude-sessions :as claude-sessions]))

(defn extract-tool-calls
  "Extract tool calls from a log file or session.

   Delegates to claude-sessions for actual parsing.
   Accepts either:
   - A file path (string or path) to a JSONL log file
   - A session-id string (will look up in Claude's projects dir)

   Returns a seq of {:name <tool-name> :input <tool-input>} maps,
   or nil if the file doesn't exist or is empty."
  [log-file-or-session-id]
  (when-let [entries (seq (claude-sessions/read-session-jsonl log-file-or-session-id))]
    (claude-sessions/extract-tool-calls entries)))

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

(defn- tool-name->display
  "Convert tool name to shorter display form."
  [tool-name]
  (cond
    (str/starts-with? tool-name "mcp__forj__")
    (str "forj:" (subs tool-name 11))

    (str/starts-with? tool-name "mcp__")
    (let [parts (str/split (subs tool-name 5) #"__" 2)]
      (if (= 2 (count parts))
        (str (first parts) ":" (second parts))
        (subs tool-name 5)))

    :else tool-name))

(defn- score->emoji
  "Convert compliance score to display emoji."
  [score]
  (case score
    :excellent "🟢"
    :good "🟡"
    :fair "🟠"
    :poor "🔴"
    "⚪"))

(defn- score->description
  "Human-readable description of the compliance score."
  [score]
  (case score
    :excellent "REPL-driven development followed"
    :good "Some REPL usage, minor issues"
    :fair "Limited REPL usage"
    :poor "Bypassed REPL workflow"
    "Unknown"))

(defn summarize-tool-usage
  "Summarize tool usage from tool calls.
   Returns a map of {:tool-name count} sorted by frequency."
  [tool-calls]
  (let [tool-calls (or tool-calls [])]
    (->> tool-calls
         (map :name)
         frequencies
         (sort-by val >)
         (into (array-map)))))

(defn format-iteration-summary
  "Format a summary of an iteration's tool usage and compliance.
   Returns a vector of strings to be printed line by line.

   Takes tool-calls seq and optional iteration number."
  [tool-calls & [iteration]]
  (let [tool-calls (or tool-calls [])
        tool-summary (summarize-tool-usage tool-calls)
        compliance (score-repl-compliance tool-calls)
        {:keys [score used-repl-tools anti-patterns]} compliance
        emoji (score->emoji score)
        total-calls (count tool-calls)]
    (vec
     (concat
      ;; Header
      ["┌─────────────────────────────────────────────────────────────"
       (str "│ " (if iteration (str "Iteration " iteration " Summary") "Iteration Summary"))
       "├─────────────────────────────────────────────────────────────"
       (str "│ Total tool calls: " total-calls)
       (str "│ REPL Compliance:  " emoji " " (name score) " - " (score->description score))
       "├─────────────────────────────────────────────────────────────"
       "│ Tools Used:"]
      ;; Tool breakdown
      (if (empty? tool-summary)
        ["│   (none)"]
        (map (fn [[tool count]]
               (str "│   " (tool-name->display tool) ": " count))
             tool-summary))
      ;; REPL tools section (if any)
      (when (seq used-repl-tools)
        ["├─────────────────────────────────────────────────────────────"
         (str "│ ✓ REPL Tools: " (str/join ", " (map #(str/replace % "mcp__forj__" "") used-repl-tools)))])
      ;; Anti-patterns section (if any)
      (when (seq anti-patterns)
        ["├─────────────────────────────────────────────────────────────"
         (str "│ ✗ Anti-patterns detected (" (count anti-patterns) "):")
         (str "│   " (str/join ", " (take 3 (map #(first (str/split % #"\s+" 2)) anti-patterns)))
              (when (> (count anti-patterns) 3) "..."))])
      ;; Footer
      ["└─────────────────────────────────────────────────────────────"]))))

(defn print-iteration-summary
  "Print a formatted summary of iteration tool usage and compliance.
   Returns the compliance map for potential further use."
  [tool-calls & [iteration]]
  (let [lines (format-iteration-summary tool-calls iteration)
        compliance (score-repl-compliance tool-calls)]
    (doseq [line lines]
      (println line))
    compliance))

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

  ;; Test summarize-tool-usage
  (summarize-tool-usage [{:name "Read"} {:name "Edit"} {:name "Read"} {:name "Bash"}])
  ;; => {"Read" 2, "Edit" 1, "Bash" 1}

  ;; Test format-iteration-summary with mock data
  (format-iteration-summary [{:name "Read"}
                             {:name "Edit"}
                             {:name "mcp__forj__reload_namespace"}
                             {:name "mcp__forj__eval_comment_block"}]
                            5)

  ;; Test print-iteration-summary with mock data
  (print-iteration-summary [{:name "Read"}
                            {:name "Edit"}
                            {:name "mcp__forj__reload_namespace"}
                            {:name "mcp__forj__eval_comment_block"}]
                           5)
  ;; Prints:
  ;; ┌─────────────────────────────────────────────────────────────
  ;; │ Iteration 5 Summary
  ;; ├─────────────────────────────────────────────────────────────
  ;; │ Total tool calls: 4
  ;; │ REPL Compliance:  🟢 excellent - REPL-driven development followed
  ;; ├─────────────────────────────────────────────────────────────
  ;; │ Tools Used:
  ;; │   Read: 1
  ;; │   Edit: 1
  ;; │   forj:reload_namespace: 1
  ;; │   forj:eval_comment_block: 1
  ;; ├─────────────────────────────────────────────────────────────
  ;; │ ✓ REPL Tools: reload_namespace, eval_comment_block
  ;; └─────────────────────────────────────────────────────────────

  ;; Test with real log file (delegates to claude-sessions)
  (print-iteration-summary
   (extract-tool-calls ".forj/logs/lisa/parallel-extract-tool-calls-1.json")
   1)
  )
