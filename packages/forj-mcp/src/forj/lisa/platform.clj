(ns forj.lisa.platform
  "Platform abstraction for Lisa Loop - supports Claude Code and OpenCode.

   Each platform has different:
   - CLI commands and flags for spawning instances
   - Output format (JSON structure)
   - Session log storage and access
   - MCP tool names for browser automation
   - Permission handling"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Platform Detection
;; =============================================================================

(defn- opencode-in-ancestors?
  "Walk up the process tree looking for 'opencode' in any ancestor command."
  []
  (try
    (loop [ph (java.lang.ProcessHandle/current)
           depth 0]
      (when (and ph (< depth 10))
        (let [parent (.parent ph)]
          (when (.isPresent parent)
            (let [p (.get parent)
                  info (.info p)
                  cmd (.command info)]
              (if (and (.isPresent cmd)
                       (str/includes? (.get cmd) "opencode"))
                true
                (recur p (inc depth))))))))
    (catch Exception _ false)))

(defn detect-platform
  "Auto-detect the calling platform.
   Checks: env vars > ancestor process name > falls back to :claude."
  []
  (cond
    ;; Env vars (if OpenCode ever sets them)
    (or (System/getenv "OPENCODE_DIR")
        (System/getenv "OPENCODE_SESSION_ID")
        (some-> (System/getenv "OPENCODE_VERSION") seq))
    :opencode

    ;; Check if opencode is in our process ancestor chain
    (opencode-in-ancestors?)
    :opencode

    :else :claude))

(defn resolve-platform
  "Resolve platform from user input or auto-detect.
   Accepts string or keyword, returns keyword."
  [platform-input]
  (if (some? platform-input)
    (keyword (name platform-input))
    (detect-platform)))

;; =============================================================================
;; Platform Configs
;; =============================================================================

(def ^:private claude-defaults
  {:platform :claude
   :cli "claude"
   :allowed-tools "Bash,Edit,Read,Write,Glob,Grep,mcp__forj__*,mcp__claude-in-chrome__*"
   :mcp-config (str (fs/home) "/.claude.json")})

(def ^:private opencode-defaults
  {:platform :opencode
   :cli "opencode"
   :agent "build"
   :allowed-tools "Bash,Edit,Read,Write,Glob,Grep,mcp__forj__*,mcp__playwright__*"})

(defn platform-config
  "Get platform-specific default config values."
  [platform]
  (case platform
    :opencode opencode-defaults
    claude-defaults))

;; =============================================================================
;; Spawn Args
;; =============================================================================

(defn build-spawn-args
  "Build CLI args for spawning an iteration instance.

   Claude: claude -p --output-format json --dangerously-skip-permissions --mcp-config ... --session-id ...
   OpenCode: opencode run --format json --dir <path> --agent build"
  [config project-path session-id]
  (let [platform (:platform config)
        verbose? (:verbose config)]
    (case platform
      :opencode
      ;; OpenCode: --format json always streams JSONL (no separate verbose mode)
      (cond-> ["opencode" "run" "--format" "json" "--dir" (str project-path)]
        (:agent config) (into ["--agent" (:agent config)]))

      ;; Claude (default)
      (cond-> ["claude" "-p"
               "--output-format" (if verbose? "stream-json" "json")
               "--dangerously-skip-permissions"
               "--mcp-config" (:mcp-config config)
               "--session-id" session-id]
        verbose? (conj "--verbose")))))

(defn spawn-input-mode
  "How to pass the prompt to the spawned process.

   Claude: stdin (prompt piped via :in)
   OpenCode: stdin (piped, appended to positional args)"
  [config]
  (:platform config))

;; =============================================================================
;; Output Parsing
;; =============================================================================

(defn- parse-claude-output
  "Parse Claude CLI output. Handles both json (single object) and stream-json (JSONL)."
  [content]
  (if (and (str/includes? content "\n{")
           (str/starts-with? (str/trim content) "{"))
    ;; stream-json: JSONL format
    (let [lines (str/split-lines content)
          parsed (keep (fn [line]
                         (when-not (str/blank? line)
                           (try (json/parse-string line true) (catch Exception _ nil))))
                       lines)
          result-msg (first (filter #(= "result" (:type %)) parsed))
          cost-msg (first (filter :total_cost_usd parsed))]
      (if result-msg
        (merge result-msg (when cost-msg (select-keys cost-msg [:total_cost_usd :usage])))
        (or (last parsed) {:is_error true :error "No result message found in stream"})))
    ;; Standard json: single object
    (json/parse-string content true)))

(defn- parse-opencode-output
  "Parse OpenCode --format json output (always JSONL events).

   Events: step_start, text, tool_use, step_finish, error
   Accumulates cost/tokens from step_finish events, text from text events."
  [content]
  (let [lines (str/split-lines content)
        parsed (keep (fn [line]
                       (when-not (str/blank? line)
                         (try (json/parse-string line true) (catch Exception _ nil))))
                     lines)
        ;; Collect text parts
        text-parts (->> parsed
                        (filter #(= "text" (:type %)))
                        (map #(get-in % [:part :text]))
                        (filter some?))
        ;; Accumulate cost and tokens from step_finish events
        step-finishes (->> parsed (filter #(= "step_finish" (:type %))))
        total-cost (->> step-finishes (map #(get-in % [:part :cost] 0)) (reduce + 0))
        total-tokens (->> step-finishes
                          (map #(get-in % [:part :tokens]))
                          (reduce (fn [acc t]
                                    (if t
                                      {:input (+ (:input acc 0) (:input t 0))
                                       :output (+ (:output acc 0) (:output t 0))}
                                      acc))
                                  {:input 0 :output 0}))
        ;; Check for errors
        errors (->> parsed (filter #(= "error" (:type %))))
        ;; Extract session ID from first event
        session-id (some :sessionID parsed)
        ;; Check final step_finish for reason
        result-text (str/join "\n" text-parts)]
    (cond
      (seq errors)
      {:is_error true
       :error (get-in (first errors) [:error :data :message] "Unknown error")
       :session-id session-id
       :total_cost_usd total-cost}

      (seq text-parts)
      {:result result-text
       :subtype "success"
       :session-id session-id
       :total_cost_usd total-cost
       :usage total-tokens}

      :else
      {:is_error true
       :error "No output from OpenCode"
       :session-id session-id
       :total_cost_usd total-cost})))

(defn parse-iteration-output
  "Parse the output from a spawned iteration. Platform-aware."
  [config log-file]
  (try
    (when-not (fs/exists? log-file)
      (throw (ex-info "Log file does not exist" {:file log-file})))
    (let [content (slurp log-file)]
      (when (str/blank? content)
        (throw (ex-info "Log file is empty" {:file log-file})))
      (case (:platform config)
        :opencode (parse-opencode-output content)
        (parse-claude-output content)))
    (catch Exception e
      {:is_error true
       :error (str "Failed to parse output: " (.getMessage e))})))

(defn extract-session-id-from-log
  "Extract the session ID from a log file's JSON events.

   Claude: session-id is pre-generated and passed as CLI arg (use the known value).
   OpenCode: session-id is in the sessionID field of each JSON event."
  [config log-file]
  (case (:platform config)
    :opencode
    (try
      (let [content (slurp (str log-file))
            first-line (first (str/split-lines content))]
        (when-not (str/blank? first-line)
          (:sessionID (json/parse-string first-line true))))
      (catch Exception _ nil))
    ;; Claude: session ID is passed to CLI, already known
    nil))

;; =============================================================================
;; Session Access (for analytics/monitoring)
;; =============================================================================

(defn read-session-transcript
  "Read the conversation transcript for a session.

   Claude: reads from ~/.claude/projects/<encoded>/<session-id>.jsonl
   OpenCode: shells out to `opencode export <session-id>`"
  [config session-id _project-path]
  (case (:platform config)
    :opencode
    (try
      (let [result (p/shell {:out :string :err :string :continue true}
                            "opencode" "export" session-id)]
        (if (zero? (:exit result))
          (let [data (json/parse-string (:out result) true)
                messages (:messages data)]
            {:session-id session-id
             :exists? true
             :transcript
             (->> messages
                  (map (fn [msg]
                         (let [role (get-in msg [:info :role])
                               parts (:parts msg)
                               text-parts (->> parts
                                               (filter #(= "text" (:type %)))
                                               (map :text))
                               tool-parts (->> parts
                                               (filter #(= "tool" (:type %)))
                                               (map (fn [tp]
                                                      {:tool (:tool tp)
                                                       :id (:callID tp)
                                                       :input-summary
                                                       (let [input (get-in tp [:state :input])]
                                                         (when input
                                                           (let [s (pr-str input)]
                                                             (if (> (count s) 200)
                                                               (str (subs s 0 200) "...")
                                                               s))))})))]
                           {:type role
                            :content (when (seq text-parts)
                                       (let [text (str/join "\n" text-parts)]
                                         (if (> (count text) 500)
                                           (str (subs text 0 500) "...")
                                           text)))
                            :tool_calls (when (seq tool-parts) tool-parts)})))
                  (filter #(or (:content %) (:tool_calls %)))
                  vec)
             :turn-count (count messages)})
          {:session-id session-id :exists? false
           :error (str "Export failed: " (:err result))}))
      (catch Exception e
        {:session-id session-id :exists? false :error (.getMessage e)}))

    ;; Claude: delegate to claude-sessions namespace (loaded dynamically to avoid circular dep)
    nil))

(defn read-session-tool-summary
  "Get tool usage summary for a session.

   Claude: reads from session JSONL
   OpenCode: parses tool events from the iteration log file"
  [config session-id-or-log-file _project-path]
  (case (:platform config)
    :opencode
    (try
      (let [content (slurp (str session-id-or-log-file))
            lines (str/split-lines content)
            parsed (keep (fn [line]
                           (when-not (str/blank? line)
                             (try (json/parse-string line true) (catch Exception _ nil))))
                         lines)
            tool-events (->> parsed
                             (filter #(= "tool_use" (:type %)))
                             (map (fn [evt]
                                    {:name (get-in evt [:part :tool])
                                     :id (get-in evt [:part :callID])})))
            tool-counts (->> tool-events
                             (map :name)
                             frequencies
                             (sort-by val >)
                             (into (array-map)))]
        {:session-id (some :sessionID parsed)
         :exists? true
         :tool-counts tool-counts
         :total-calls (count tool-events)
         :entry-count (count parsed)})
      (catch Exception e
        {:exists? false :error (.getMessage e)}))

    ;; Claude: delegate to claude-sessions namespace
    nil))

;; =============================================================================
;; Browser Tools Prompt
;; =============================================================================

(defn browser-tools-prompt
  "Build platform-specific browser automation instructions for UI checkpoints.
   Returns a string to include in iteration prompts, or nil if no browser tools."
  [config]
  (case (:platform config)
    :opencode
    (str "\n### Step 4: Visual Validation - DO NOT SKIP\n"
         "**For UI checkpoints, you MUST take a screenshot:**\n\n"
         "**Playwright MCP Workflow** (use these exact steps):\n"
         "```\n"
         "1. mcp__playwright__browser_navigate to the app URL\n"
         "2. mcp__playwright__browser_wait_for with time=3 (wait for render)\n"
         "3. mcp__playwright__browser_take_screenshot\n"
         "```\n\n"
         "Check shadow-cljs.edn :dev-http, package.json scripts, or LISA_PLAN.edn for the correct URL/port.\n\n"
         "CHECKPOINT WILL BE REJECTED if you mark complete without screenshot verification.")

    ;; Claude: Chrome MCP primary, Playwright as fallback
    (str "\n### Step 4: Visual Validation - DO NOT SKIP\n"
         "**For UI checkpoints, you MUST take a screenshot:**\n\n"
         "**Chrome MCP Workflow** (use these exact steps):\n"
         "```\n"
         "1. mcp__claude-in-chrome__tabs_context_mcp with createIfEmpty=true\n"
         "2. mcp__claude-in-chrome__tabs_create_mcp (creates a fresh tab, returns tabId)\n"
         "3. mcp__claude-in-chrome__navigate with url and tabId\n"
         "4. mcp__claude-in-chrome__browser_wait_for with time=3 (wait for render)\n"
         "5. mcp__claude-in-chrome__computer with action='screenshot' and tabId\n"
         "```\n\n"
         "**Alternative - Playwright MCP**:\n"
         "```\n"
         "1. mcp__playwright__browser_navigate to the app URL\n"
         "2. mcp__playwright__browser_wait_for with time=3\n"
         "3. mcp__playwright__browser_take_screenshot\n"
         "```\n\n"
         "Check shadow-cljs.edn :dev-http, package.json scripts, or LISA_PLAN.edn for the correct URL/port.\n\n"
         "CHECKPOINT WILL BE REJECTED if you mark complete without screenshot verification.")))

;; =============================================================================
;; Agent Teams Feature Detection
;; =============================================================================

(defn agent-teams-available?
  "Check if agent teams feature is available for the given platform.

   Claude: requires CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS env var
   OpenCode: not yet supported"
  [config]
  (case (:platform config)
    :opencode
    {:available false
     :reason "Agent Teams is not yet supported in OpenCode. Use sequential or parallel Lisa Loop instead."}

    ;; Claude: check env var
    (let [enabled? (let [val (System/getenv "FORJ_AGENT_TEAMS")]
                     (boolean (and val (#{"1" "true" "yes"} (str/lower-case val)))))
          experimental-flag (System/getenv "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS")]
      (cond
        (not enabled?)
        {:available false
         :reason "FORJ_AGENT_TEAMS env var not set. Set FORJ_AGENT_TEAMS=1 to enable."}

        (not experimental-flag)
        {:available false
         :reason "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS not set. Enable in ~/.claude/settings.json."}

        :else
        {:available true :reason nil}))))

(comment
  ;; Test platform detection
  (detect-platform)

  ;; Test platform configs
  (platform-config :claude)
  (platform-config :opencode)

  ;; Test spawn args
  (build-spawn-args (platform-config :claude) "/home/user/project" "uuid-123")
  ;; => ["claude" "-p" "--output-format" "json" "--dangerously-skip-permissions" ...]

  (build-spawn-args (platform-config :opencode) "/home/user/project" nil)
  ;; => ["opencode" "run" "--format" "json" "--dir" "/home/user/project" "--agent" "build"]

  ;; Test OpenCode output parsing
  (parse-opencode-output
   (str "{\"type\":\"step_start\",\"sessionID\":\"ses_abc\"}\n"
        "{\"type\":\"text\",\"sessionID\":\"ses_abc\",\"part\":{\"type\":\"text\",\"text\":\"Hello\"}}\n"
        "{\"type\":\"step_finish\",\"sessionID\":\"ses_abc\",\"part\":{\"type\":\"step-finish\",\"reason\":\"stop\",\"cost\":0.01,\"tokens\":{\"input\":100,\"output\":50}}}\n"))
  )
