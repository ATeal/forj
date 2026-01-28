(ns forj.mcp.tools
  "MCP tool implementations for REPL connectivity."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [forj.lisa.claude-sessions :as claude-sessions]
            [forj.lisa.plan :as lisa-plan]
            [forj.lisa.plan-edn :as plan-edn]
            [forj.lisa.signs :as lisa-signs]
            [forj.lisa.validation :as lisa-validation]
            [forj.scaffold :as scaffold]))

(def tools
  "Tool definitions for MCP tools/list response."
  [{:name "repl_eval"
    :description "Evaluate Clojure code in an nREPL server. Returns the evaluation result or error. Provide 'file' for automatic REPL type selection (clj/cljs/bb) based on file path."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"
                                      :description "Clojure code to evaluate"}
                               :port {:type "integer"
                                      :description "nREPL port (auto-discovered if not provided)"}
                               :file {:type "string"
                                      :description "Optional file path for REPL type auto-selection (e.g., .cljs files use shadow REPL)"}
                               :timeout {:type "integer"
                                         :description "Timeout in milliseconds (default: 120000)"}}
                  :required ["code"]}}

   {:name "discover_repls"
    :description "Find running nREPL servers in the current project. Checks .nrepl-port, .shadow-cljs/nrepl.port, etc."
    :inputSchema {:type "object"
                  :properties {}}}

   {:name "analyze_project"
    :description "Analyze a Clojure project. Returns available bb tasks, deps.edn aliases, and shadow-cljs builds."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}}}}

   {:name "reload_namespace"
    :description "Reload a Clojure namespace in the REPL. Like ,ef in Neovim - reloads the file to pick up changes."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"
                                    :description "Namespace to reload (e.g., 'forj.mcp.tools') or file path (e.g., 'src/forj/mcp/tools.clj')"}
                               :port {:type "integer"
                                      :description "nREPL port (auto-discovered if not provided)"}}
                  :required ["ns"]}}

   {:name "doc_symbol"
    :description "Look up documentation for a Clojure symbol. Like pressing K in Conjure - returns docstring, arglist, and optionally source."
    :inputSchema {:type "object"
                  :properties {:symbol {:type "string"
                                        :description "Symbol to look up (e.g., 'map', 'clojure.string/split', 'forj.mcp.tools/eval-code')"}
                               :include-source {:type "boolean"
                                                :description "Include full source code (default: false)"}
                               :port {:type "integer"
                                      :description "nREPL port (auto-discovered if not provided)"}}
                  :required ["symbol"]}}

   {:name "eval_at"
    :description "Evaluate a form at a specific line in a file. Like ,er (root) or ,ee (inner) in Conjure."
    :inputSchema {:type "object"
                  :properties {:file {:type "string"
                                      :description "File path to evaluate from"}
                               :line {:type "integer"
                                      :description "Line number (1-based)"}
                               :scope {:type "string"
                                       :enum ["root" "inner"]
                                       :description "root = top-level form like ,er (default), inner = innermost form like ,ee"}
                               :port {:type "integer"
                                      :description "nREPL port (auto-discovered if not provided)"}}
                  :required ["file" "line"]}}

   {:name "run_tests"
    :description "Run tests for a Clojure/ClojureScript project. Auto-detects test runner (bb test, clj -M:test, shadow-cljs compile test, lein test)."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}
                               :namespace {:type "string"
                                           :description "Specific namespace to test (optional)"}
                               :runner {:type "string"
                                        :enum ["bb" "clj" "shadow" "lein" "auto"]
                                        :description "Test runner to use (auto-detected by default)"}}}}

   {:name "eval_comment_block"
    :description "Evaluate all forms inside a (comment ...) block. Finds comment block at or near the given line and evaluates each form inside it sequentially."
    :inputSchema {:type "object"
                  :properties {:file {:type "string"
                                      :description "File path containing the comment block"}
                               :line {:type "integer"
                                      :description "Line number at or near the comment block (1-based)"}
                               :port {:type "integer"
                                      :description "nREPL port (auto-discovered if not provided)"}}
                  :required ["file" "line"]}}

   {:name "validate_changed_files"
    :description "Validate changed Clojure files by reloading namespaces and evaluating comment blocks. Ideal for Lisa Loop validation before running tests. Uses git to detect changed files if no files specified."
    :inputSchema {:type "object"
                  :properties {:files {:type "array"
                                       :items {:type "string"}
                                       :description "List of file paths to validate (optional - uses git diff if not provided)"}
                               :port {:type "integer"
                                      :description "nREPL port (auto-discovered if not provided)"}}}}

   {:name "validate_project"
    :description "Validate a Clojure project setup. Checks bb.edn warnings, deps resolution, npm install status, and Java version for shadow-cljs. Run after scaffolding to catch common issues."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}
                               :fix {:type "boolean"
                                     :description "Attempt to auto-fix issues (default: false)"}}}}

   {:name "view_repl_logs"
    :description "View REPL process logs from .forj/logs/. Returns recent output from backend, shadow-cljs, and expo logs. Use this to debug issues across the full stack."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}
                               :log {:type "string"
                                     :enum ["all" "backend" "shadow" "expo"]
                                     :description "Which log to view: all (default), backend, shadow, or expo"}
                               :lines {:type "integer"
                                       :description "Number of lines to return (default: 50)"}}}}

   {:name "scaffold_project"
    :description "Create a new Clojure project from composable modules. Merges config files, substitutes version placeholders, and writes output files."
    :inputSchema {:type "object"
                  :properties {:project_name {:type "string"
                                              :description "Name of the project (e.g., 'my-app')"}
                               :modules {:type "array"
                                         :items {:type "string"}
                                         :description "Module names to include: 'script', 'backend', 'web', 'mobile', 'db-postgres', 'db-sqlite'"}
                               :output_path {:type "string"
                                             :description "Directory to create project in (default: current directory)"}}
                  :required ["project_name" "modules"]}}

   ;; Process tracking tools
   {:name "track_process"
    :description "Track a started process for later cleanup. Call this after starting each background process (REPL, shadow-cljs, Expo). Processes are tracked per-project so /clj-repl stop can kill them."
    :inputSchema {:type "object"
                  :properties {:pid {:type "integer"
                                     :description "Process ID of the started process"}
                               :name {:type "string"
                                      :description "Human-readable name (e.g., 'backend-repl', 'shadow-cljs', 'expo')"}
                               :port {:type "integer"
                                      :description "Port the process is listening on (optional)"}
                               :command {:type "string"
                                         :description "Command that was used to start the process (for reference)"}}
                  :required ["pid" "name"]}}

   {:name "stop_project"
    :description "Stop all tracked processes for the current project. Kills REPLs, shadow-cljs, Expo, etc. that were started with /clj-repl. Use this when the user says 'stop' or when ending a session."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}}}}

   {:name "list_tracked_processes"
    :description "List all tracked processes for the current project. Shows what's been started and can be stopped with stop_project."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}}}}

   ;; Lisa Loop v2 tools (planning + orchestration)
   {:name "lisa_create_plan"
    :description "Create a LISA_PLAN.edn with checkpoints for a task. Supports dependency graphs between checkpoints."
    :inputSchema {:type "object"
                  :properties {:title {:type "string"
                                       :description "Plan title (e.g., 'Build user authentication')"}
                               :checkpoints {:type "array"
                                             :items {:type "object"
                                                     :properties {:id {:type "string"
                                                                       :description "Unique keyword ID (e.g., 'auth-module'). Auto-generated if not provided."}
                                                                  :description {:type "string"}
                                                                  :file {:type "string"}
                                                                  :acceptance {:type "string"}
                                                                  :gates {:type "array"
                                                                          :items {:type "string"}
                                                                          :description "Validation gates (e.g., 'repl:(fn) => expected')"}
                                                                  :depends_on {:type "array"
                                                                               :items {:type "string"}
                                                                               :description "IDs of checkpoints this depends on"}}}
                                             :description "List of checkpoints with optional dependencies"}
                               :path {:type "string"
                                      :description "Project path (defaults to current directory)"}}
                  :required ["title" "checkpoints"]}}

   {:name "lisa_get_plan"
    :description "Read the current plan (LISA_PLAN.edn or .md) and return optimized context for iteration. Includes dependency info, ready/blocked checkpoints, and recent signs."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}
                               :max_signs {:type "integer"
                                           :description "Max number of recent signs to include (default: 5)"}
                               :full {:type "boolean"
                                      :description "Return full plan instead of compressed context (default: false)"}}}}

   {:name "lisa_mark_checkpoint_done"
    :description "Mark a checkpoint as done. Automatically advances to the next ready checkpoint based on dependencies."
    :inputSchema {:type "object"
                  :properties {:checkpoint {:type "string"
                                            :description "Checkpoint ID (keyword like 'auth-module') or number"}
                               :path {:type "string"
                                      :description "Project path (defaults to current directory)"}}
                  :required ["checkpoint"]}}

   {:name "lisa_add_checkpoint"
    :description "Add a checkpoint to a running Lisa plan. Position is auto-determined: inserts after dependencies if specified, after current in-progress checkpoint if loop is running, or at end otherwise."
    :inputSchema {:type "object"
                  :properties {:id {:type "string"
                                    :description "Checkpoint ID (will be converted to keyword, e.g., 'fix-bug' becomes :fix-bug)"}
                               :description {:type "string"
                                             :description "What this checkpoint should accomplish"}
                               :file {:type "string"
                                      :description "Target file to modify (optional)"}
                               :acceptance {:type "string"
                                            :description "Acceptance criteria - how to know it's done (optional)"}
                               :gates {:type "array"
                                       :items {:type "string"}
                                       :description "Validation gates like 'repl:(run-tests)' (optional)"}
                               :depends_on {:type "array"
                                            :items {:type "string"}
                                            :description "Checkpoint IDs this depends on (optional, also affects auto-positioning)"}
                               :position {:type "string"
                                          :description "Override auto-positioning: 'end', 'next', or checkpoint ID. Usually not needed."}
                               :path {:type "string"
                                      :description "Project path (defaults to current directory)"}}
                  :required ["id" "description"]}}

   {:name "lisa_run_orchestrator"
    :description "Run the Lisa Loop orchestrator. Spawns fresh Claude instances for each iteration until all checkpoints complete. Includes timeout detection (15 min per iteration, 5 min idle) to recover from stuck iterations."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}
                               :max_iterations {:type "integer"
                                                :description "Maximum iterations before stopping (default: 20)"}
                               :parallel {:type "boolean"
                                          :description "Enable parallel execution for EDN plans (default: true)"}
                               :max_parallel {:type "integer"
                                              :description "Max concurrent checkpoints when parallel (default: 3)"}
                               :verbose {:type "boolean"
                                         :description "Use stream-json for detailed tool call logs (default: false)"}
                               :iteration_timeout {:type "integer"
                                                   :description "Timeout per iteration in seconds (disabled by default)"}
                               :idle_timeout {:type "integer"
                                              :description "Kill if no file activity for N seconds (default: 300 = 5 min)"}
                               :no_timeout {:type "boolean"
                                            :description "Disable all timeouts (default: false)"}}}}

   {:name "repl_snapshot"
    :description "Take a snapshot of current REPL state. Returns loaded namespaces, defined vars in project namespaces, and running servers. Use this to understand what's live in the REPL."
    :inputSchema {:type "object"
                  :properties {:port {:type "integer"
                                      :description "nREPL port (auto-discovered if not provided)"}
                               :namespace {:type "string"
                                           :description "Specific namespace to inspect (optional, inspects all project namespaces if not provided)"}}}}

   ;; Signs (guardrails) tools
   {:name "lisa_append_sign"
    :description "Append a sign (learning) to LISA_SIGNS.md. Signs record failures and learnings for future iterations to avoid repeating mistakes."
    :inputSchema {:type "object"
                  :properties {:iteration {:type "integer"
                                           :description "Current iteration number"}
                               :checkpoint {:type "string"
                                            :description "Checkpoint description (e.g., '2 - Create JWT module')"}
                               :issue {:type "string"
                                       :description "What went wrong"}
                               :fix {:type "string"
                                     :description "How to avoid this in the future"}
                               :severity {:type "string"
                                          :enum ["error" "warning"]
                                          :description "Severity level (default: error)"}
                               :path {:type "string"
                                      :description "Project path (defaults to current directory)"}}
                  :required ["issue" "fix"]}}

   {:name "lisa_get_signs"
    :description "Read LISA_SIGNS.md and return recent signs (learnings from previous iterations)."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}}}}

   {:name "lisa_clear_signs"
    :description "Delete LISA_SIGNS.md. Typically used when starting a new loop or after loop completion."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}}}}

   ;; Validation tools
   {:name "lisa_run_validation"
    :description "Run validation checks for a checkpoint. Supports REPL expressions, Chrome MCP actions, and LLM-as-judge criteria. Format: 'repl:(fn arg) => expected | chrome:screenshot /path | judge:Is this clear?'"
    :inputSchema {:type "object"
                  :properties {:validation {:type "string"
                                            :description "Validation string with items separated by |"}
                               :port {:type "integer"
                                      :description "nREPL port for REPL validations (auto-discovered if not provided)"}
                               :path {:type "string"
                                      :description "Project path (defaults to current directory)"}}
                  :required ["validation"]}}

   {:name "lisa_check_gates"
    :description "Check if all gates for a checkpoint have passed. Gates are strict validations that must pass before advancing to the next checkpoint."
    :inputSchema {:type "object"
                  :properties {:gates {:type "string"
                                       :description "Gates string with items separated by |"}
                               :port {:type "integer"
                                      :description "nREPL port for REPL validations (auto-discovered if not provided)"}
                               :path {:type "string"
                                      :description "Project path (defaults to current directory)"}}
                  :required ["gates"]}}

   {:name "lisa_watch"
    :description "Get Lisa Loop status for monitoring. Returns checkpoint table, current iteration, elapsed time, cost, and file activity. Use this to watch loop progress."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}}}}])

;; =============================================================================
;; Input Validation
;; =============================================================================

(def ^:private valid-runners
  "Valid runner values for run-tests."
  #{"bb" "clj" "shadow" "lein" "auto"})

(defn- validate-file-exists
  "Validate that a file exists. Returns nil if valid, or an error map if not."
  [file-path param-name]
  (cond
    (nil? file-path)
    {:success false
     :error (str "Missing required parameter: " param-name)}

    (str/blank? file-path)
    {:success false
     :error (str "Parameter '" param-name "' cannot be empty")}

    (not (fs/exists? file-path))
    {:success false
     :error (str "File not found: " file-path)}

    (fs/directory? file-path)
    {:success false
     :error (str "Expected a file but got a directory: " file-path)}

    :else nil))

(defn- validate-runner
  "Validate that runner is a valid enum value. Returns nil if valid, or an error map if not."
  [runner]
  (when (and runner (not (contains? valid-runners runner)))
    {:success false
     :error (str "Invalid runner: '" runner "'. Must be one of: " (str/join ", " (sort valid-runners)))}))

;; =============================================================================
;; Shell Execution Helper
;; =============================================================================

(defn shell-execute
  "Execute a shell command with standard options for capturing output.
   Returns {:exit <code> :out <string> :err <string>}.

   Options:
     :dir - Working directory for the command

   Examples:
     (shell-execute \"git\" \"status\")
     (shell-execute {:dir \"/some/path\"} \"npm\" \"install\")
     (shell-execute [\"clj\" \"-M:test\"])
     (shell-execute {:dir path} cmd-vec)"
  [& args]
  (let [[opts cmd-args] (if (map? (first args))
                          [(first args) (rest args)]
                          [{} args])
        cmd-seq (if (and (= 1 (count cmd-args))
                         (sequential? (first cmd-args)))
                  (first cmd-args)
                  cmd-args)
        shell-opts (merge {:out :string :err :string :continue true}
                          (select-keys opts [:dir]))]
    (apply p/shell shell-opts cmd-seq)))

;; =============================================================================
;; Error Handling Helper
;; =============================================================================

(defn safe-execute
  "Wrap an operation in try-catch, returning standardized error maps on exception.

   Arguments:
     f          - A no-arg function to execute
     error-msg  - Prefix for error message (e.g., \"Failed to discover ports\")

   Returns:
     - The result of (f) if successful
     - {:success false :error \"<error-msg>: <exception message>\"} on exception

   Examples:
     (safe-execute #(risky-operation) \"Operation failed\")
     (safe-execute (fn [] (slurp file)) \"Failed to read file\")"
  [f error-msg]
  (try
    (f)
    (catch Exception e
      {:success false
       :error (str error-msg ": " (.getMessage e))})))

;; =============================================================================
;; REPL Type Detection (Path-based routing)
;; =============================================================================

(defn detect-repl-type
  "Detect the appropriate REPL type for a given file path.
   Returns :clojure, :clojurescript, :babashka, or :unknown.

   Rules:
   1. File extension: .cljs → clojurescript, .bb → babashka
   2. Path patterns: /cljs/, /clojurescript/ → clojurescript
   3. Project context: bb.edn only → babashka, shadow-cljs.edn → has cljs
   4. .cljc files: check context or default to :clojure"
  [file-path]
  (let [ext (re-find #"\.[^.]+$" file-path)
        path-lower (str/lower-case file-path)]
    (cond
      ;; Explicit extensions
      (= ext ".cljs") :clojurescript
      (= ext ".bb") :babashka

      ;; Path patterns suggesting ClojureScript
      (or (str/includes? path-lower "/cljs/")
          (str/includes? path-lower "/clojurescript/")
          (str/includes? path-lower "src-cljs/")) :clojurescript

      ;; Path patterns suggesting Babashka
      (or (str/includes? path-lower "/bb/")
          (str/includes? path-lower "/scripts/")
          (str/includes? path-lower "/tasks/")) :babashka

      ;; .clj or .cljc - check project context
      (#{".clj" ".cljc"} ext)
      (let [;; Check for project markers
            has-bb? (or (.exists (java.io.File. "bb.edn"))
                        (.exists (java.io.File. "../bb.edn")))
            has-deps? (or (.exists (java.io.File. "deps.edn"))
                          (.exists (java.io.File. "../deps.edn")))
            has-shadow? (or (.exists (java.io.File. "shadow-cljs.edn"))
                            (.exists (java.io.File. "../shadow-cljs.edn")))]
        (cond
          ;; Pure babashka project
          (and has-bb? (not has-deps?) (not has-shadow?)) :babashka
          ;; Has shadow-cljs and path suggests frontend
          (and has-shadow?
               (or (str/includes? path-lower "frontend")
                   (str/includes? path-lower "client"))) :clojurescript
          ;; Default to clojure for .clj/.cljc
          :else :clojure))

      :else :unknown)))

(defn- normalize-repl-type
  "Normalize REPL type keywords between detection and discovery.
   detect-repl-type returns :clojure/:clojurescript/:babashka
   discover_repls returns :clj/:cljs/:bb"
  [detected-type]
  (case detected-type
    :clojure :clj
    :clojurescript :cljs
    :babashka :bb
    detected-type))

(defn select-repl-for-file
  "Given discovered REPLs and a file path, select the best matching REPL.
   Returns the port number or nil if no suitable REPL found."
  [repls-output file-path]
  (let [detected-type (detect-repl-type file-path)
        normalized-type (normalize-repl-type detected-type)
        lines (str/split-lines repls-output)
        ;; Parse REPL entries: "localhost:PORT (type)"
        parse-repl (fn [line]
                     (when-let [[_ port type] (re-find #"localhost:(\d+)\s+\((\w+)\)" line)]
                       {:port (parse-long port)
                        :type (keyword type)}))
        repls (->> lines
                   (map parse-repl)
                   (remove nil?))]
    ;; Match REPL type
    (or
     ;; Exact match
     (:port (first (filter #(= (:type %) normalized-type) repls)))
     ;; Fallback: bb can often run clj code
     (when (= normalized-type :clj)
       (:port (first (filter #(= (:type %) :bb) repls))))
     ;; Last resort: first available
     (:port (first repls)))))

(defn discover-repls
  "Find running nREPL servers using clj-nrepl-eval --discover-ports."
  []
  (try
    (let [result (shell-execute "clj-nrepl-eval" "--discover-ports")]
      (if (zero? (:exit result))
        {:success true
         :ports (str/trim (:out result))}
        {:success false
         :error (or (not-empty (:err result))
                    "No nREPL servers found")}))
    (catch Exception e
      {:success false
       :error (str "Failed to discover ports: " (.getMessage e))})))

(defn- extract-ports
  "Extract port numbers from discover-repls output, prioritizing current directory."
  [output]
  (let [lines (str/split-lines output)
        ;; Find ports in "localhost:PORT" format
        port-pattern #"localhost:(\d+)"
        ;; Split into current dir and other sections
        current-dir-idx (some #(when (str/includes? (second %) "current directory")
                                 (first %))
                              (map-indexed vector lines))
        other-dir-idx (some #(when (str/includes? (second %) "other directories")
                               (first %))
                            (map-indexed vector lines))]
    (->> lines
         ;; Take lines between "current directory" and "other directories" first
         (drop (or current-dir-idx 0))
         (take (if (and current-dir-idx other-dir-idx)
                 (- other-dir-idx current-dir-idx)
                 100))
         (mapcat #(re-seq port-pattern %))
         (map second)
         (map parse-long)
         (remove nil?))))

(defn eval-code
  "Evaluate Clojure code via clj-nrepl-eval.
   When 'file' is provided, uses path-based REPL selection (clj/cljs/bb)."
  [{:keys [code port file timeout]}]
  (if-not port
    ;; Auto-discover port if not provided
    (let [{:keys [success ports error]} (discover-repls)]
      (if success
        ;; Use path-based selection if file provided, otherwise first port
        (if-let [selected-port (if file
                                 (select-repl-for-file ports file)
                                 (first (extract-ports ports)))]
          (eval-code {:code code :port selected-port :timeout timeout})
          {:success false
           :error (if file
                    (let [detected-type (detect-repl-type file)]
                      (str "No " (name detected-type) " REPL running. Start one with /clj-repl"))
                    "No nREPL server running. Start one with /clj-repl or in a terminal: bb nrepl-server 1667")})
        {:success false :error error}))
    ;; Evaluate with provided port
    (try
      (let [args (cond-> ["clj-nrepl-eval" "-p" (str port)]
                   timeout (conj "-t" (str timeout))
                   true (conj code))
            result (shell-execute args)]
        (if (zero? (:exit result))
          {:success true
           :value (str/trim (:out result))}
          {:success false
           :error (or (not-empty (str/trim (:err result)))
                      (str/trim (:out result))
                      "Evaluation failed")}))
      (catch Exception e
        {:success false
         :error (str "Evaluation failed: " (.getMessage e))}))))

(defn- file-path->namespace
  "Convert a file path to a namespace name.
   e.g., 'src/forj/mcp/tools.clj' -> 'forj.mcp.tools'
         'packages/forj-mcp/src/forj/mcp/tools.clj' -> 'forj.mcp.tools'"
  [path]
  (-> path
      ;; Strip common source prefixes
      (str/replace #"^.*/src/" "")
      (str/replace #"^.*/test/" "")
      (str/replace #"^src/" "")
      (str/replace #"^test/" "")
      ;; Remove file extension
      (str/replace #"\.(clj[cs]?)$" "")
      ;; Convert path separators to dots, underscores to hyphens
      (str/replace "/" ".")
      (str/replace "_" "-")))

(defn reload-namespace
  "Reload a namespace in the REPL."
  [{:keys [ns port]}]
  (let [ns-name (if (str/includes? ns "/")
                  (file-path->namespace ns)
                  ns)
        code (str "(require '" ns-name " :reload)")
        result (eval-code {:code code :port port})]
    (if (:success result)
      {:success true
       :namespace ns-name
       :message (str "Reloaded " ns-name)}
      result)))

(defn- find-top-level-forms
  "Parse file and return vector of {:start-line :end-line :form} for each top-level form.
   Uses edamame for proper Clojure parsing with location metadata."
  [content]
  (try
    (let [forms (edamame/parse-string-all content
                                          {:all true
                                           :row-key :line
                                           :col-key :col
                                           :end-row-key :end-line
                                           :end-col-key :end-col})]
      (->> forms
           (filter #(and (meta %) (:line (meta %))))
           (map (fn [form]
                  (let [m (meta form)
                        start-line (:line m)
                        end-line (:end-line m)
                        ;; Extract the form text from content
                        lines (str/split-lines content)
                        form-lines (subvec (vec lines) (dec start-line) end-line)]
                    {:start-line start-line
                     :end-line end-line
                     :form (str/join "\n" form-lines)})))))
    (catch Exception _
      ;; Fallback: return empty if parsing fails
      [])))

(defn- extract-ns-from-content
  "Extract namespace name from file content."
  [content]
  (when-let [match (re-find #"\(ns\s+([^\s\)\(]+)" content)]
    (second match)))

(defn- find-form-at-line
  "Find the top-level form containing the given line number."
  [forms line]
  (first (filter #(and (<= (:start-line %) line)
                       (>= (:end-line %) line))
                 forms)))

(defn- find-innermost-form
  "Recursively find the innermost form containing the given line."
  [form line content-lines]
  (let [m (meta form)]
    (when (and m (:line m) (:end-line m)
               (<= (:line m) line)
               (>= (:end-line m) line))
      ;; This form contains the line - check children for a more specific match
      (let [children (cond
                       (list? form) (seq form)
                       (vector? form) (seq form)
                       (map? form) (concat (keys form) (vals form))
                       (set? form) (seq form)
                       :else nil)
            inner-match (some #(find-innermost-form % line content-lines) children)]
        (or inner-match
            ;; No child contains it, this is the innermost
            {:start-line (:line m)
             :end-line (:end-line m)
             :form (str/join "\n" (subvec content-lines
                                          (dec (:line m))
                                          (:end-line m)))})))))

(defn- find-inner-form-at-line
  "Parse file and find the innermost form at the given line."
  [content line]
  (try
    (let [forms (edamame/parse-string-all content
                                          {:all true
                                           :row-key :line
                                           :col-key :col
                                           :end-row-key :end-line
                                           :end-col-key :end-col})
          content-lines (vec (str/split-lines content))]
      ;; Find first top-level form containing line, then drill down
      (some #(find-innermost-form % line content-lines) forms))
    (catch Exception _
      nil)))

(defn eval-at
  "Evaluate a form at a specific line in a file. Like ,er (root) or ,ee (inner) in Conjure.
   Uses path-based REPL routing when port is not explicitly provided."
  [{:keys [file line scope port] :or {scope "root"}}]
  (if-let [validation-error (validate-file-exists file "file")]
    validation-error
    (try
      (let [;; Detect file type and find appropriate REPL
            detected-type (detect-repl-type file)
            effective-port (or port
                               (let [{:keys [success ports]} (discover-repls)]
                                 (when success
                                   (select-repl-for-file ports file))))
            content (slurp file)
            ns-name (extract-ns-from-content content)
            target-form (if (= scope "inner")
                          (find-inner-form-at-line content line)
                          (let [forms (find-top-level-forms content)]
                            (find-form-at-line forms line)))]
        (if target-form
          (if effective-port
            (let [;; Prepend ns switch if we found a namespace
                  code (if ns-name
                         (str "(ns " ns-name ") " (:form target-form))
                         (:form target-form))
                  result (eval-code {:code code :port effective-port})]
              (if (:success result)
                {:success true
                 :file file
                 :lines [(:start-line target-form) (:end-line target-form)]
                 :namespace ns-name
                 :repl-type (name detected-type)
                 :port effective-port
                 :value (:value result)}
                result))
            {:success false
             :error (str "No " (name detected-type) " REPL running. Start one with /clj-repl or in a terminal: "
                         (case detected-type
                           :clojure "clj -M:dev"
                           :clojurescript "npx shadow-cljs watch app"
                           :babashka "bb nrepl-server 1667"
                           "bb nrepl-server 1667"))})
          {:success false
           :error (str "No form found at line " line)}))
      (catch Exception e
        {:success false
         :error (str "Failed to eval at line: " (.getMessage e))}))))

(defn doc-symbol
  "Look up documentation for a symbol."
  [{:keys [symbol include-source port]}]
  ;; Use with-out-str to capture doc output cleanly
  (let [doc-code (str "(with-out-str (clojure.repl/doc " symbol "))")
        doc-result (eval-code {:code doc-code :port port})]
    (if (:success doc-result)
      (let [doc-output (-> (:value doc-result)
                           ;; Strip the => prefix and trailing decorations
                           (str/replace #"^=> \"" "")
                           (str/replace #"\"\n\*========.*$" "")
                           ;; Unescape the string
                           (str/replace "\\n" "\n")
                           (str/replace "\\\"" "\"")
                           str/trim)]
        (if (or (str/blank? doc-output)
                (str/includes? doc-output "Unable to resolve"))
          {:success false :error (str "Symbol not found: " symbol)}
          (merge
           {:success true
            :symbol symbol
            :doc doc-output}
           ;; Optionally get source
           (when include-source
             (let [src-code (str "(clojure.repl/source-fn '" symbol ")")
                   src-result (eval-code {:code src-code :port port})]
               (when (:success src-result)
                 {:source (-> (:value src-result)
                              (str/replace #"^=> \"?" "")
                              (str/replace #"\"?\n\*========.*$" "")
                              (str/replace "\\n" "\n")
                              (str/replace "\\\"" "\""))}))))))
      doc-result)))

(defn analyze-project
  "Analyze a Clojure project for bb tasks, deps aliases, and shadow builds."
  [{:keys [path] :or {path "."}}]
  (try
    (let [read-edn (fn [file]
                     (let [f (java.io.File. path file)]
                       (when (.exists f)
                         (try
                           (read-string (slurp f))
                           (catch Exception _ nil)))))
          bb-edn (read-edn "bb.edn")
          deps-edn (read-edn "deps.edn")
          shadow-edn (read-edn "shadow-cljs.edn")]
      {:success true
       :bb-tasks (some-> bb-edn :tasks keys vec)
       :deps-aliases (some-> deps-edn :aliases keys vec)
       :shadow-builds (some-> shadow-edn :builds keys vec)
       :project-types (cond-> #{}
                        bb-edn (conj :babashka)
                        deps-edn (conj :clojure)
                        shadow-edn (conj :clojurescript))})
    (catch Exception e
      {:success false
       :error (str "Failed to analyze project: " (.getMessage e))})))

(defn- has-shadow-test-build?
  "Check if shadow-cljs.edn has a :test build."
  [path]
  (let [shadow-file (java.io.File. path "shadow-cljs.edn")]
    (when (.exists shadow-file)
      (try
        (let [content (edamame/parse-string (slurp shadow-file))]
          (contains? (:builds content) :test))
        (catch Exception _ false)))))

(defn- detect-test-runner
  "Detect the appropriate test runner for a project.
   Returns :bb, :clj, :shadow, or :lein."
  [path]
  (let [file-exists? (fn [f] (.exists (java.io.File. path f)))]
    (cond
      (file-exists? "bb.edn") :bb
      ;; Check for shadow-cljs with :test build
      (and (file-exists? "shadow-cljs.edn")
           (has-shadow-test-build? path)) :shadow
      (file-exists? "deps.edn") :clj
      (file-exists? "project.clj") :lein
      :else nil)))

(defn- build-test-command
  "Build the test command based on runner and options."
  [runner namespace]
  (case runner
    :bb (if namespace
          ["bb" "test" "--namespace" namespace]
          ["bb" "test"])
    :clj (if namespace
           ["clojure" "-M:test" "-n" namespace]
           ["clojure" "-M:test"])
    :shadow (if namespace
              ;; For shadow-cljs, namespace filtering requires custom setup
              ;; Just run the :test build for now
              ["npx" "shadow-cljs" "compile" "test"]
              ["npx" "shadow-cljs" "compile" "test"])
    :lein (if namespace
            ["lein" "test" namespace]
            ["lein" "test"])
    nil))

(defn run-tests
  "Run tests for a Clojure project."
  [{:keys [path namespace runner] :or {path "." runner "auto"}}]
  (if-let [validation-error (validate-runner runner)]
    validation-error
    (try
      (let [effective-runner (if (= runner "auto")
                               (detect-test-runner path)
                               (keyword runner))
            cmd (build-test-command effective-runner namespace)]
        (if cmd
          (let [result (shell-execute {:dir path} cmd)
                output (str (:out result) (:err result))]
            (if (zero? (:exit result))
              {:success true
               :runner (name effective-runner)
               :output output
               :passed true}
              {:success true  ; Tool succeeded, tests failed
               :runner (name effective-runner)
               :output output
               :passed false
               :exit-code (:exit result)}))
          {:success false
           :error "Could not detect test runner. No bb.edn, deps.edn, or project.clj found."}))
      (catch Exception e
        {:success false
         :error (str "Failed to run tests: " (.getMessage e))}))))

(defn- find-comment-blocks
  "Find all (comment ...) blocks in file content with their line ranges."
  [content]
  (try
    (let [forms (edamame/parse-string-all content
                                          {:all true
                                           :row-key :line
                                           :col-key :col
                                           :end-row-key :end-line
                                           :end-col-key :end-col})
          content-lines (vec (str/split-lines content))]
      (->> forms
           (filter #(and (list? %)
                         (= 'comment (first %))
                         (meta %)))
           (map (fn [form]
                  (let [m (meta form)]
                    {:start-line (:line m)
                     :end-line (:end-line m)
                     :forms (rest form)  ; Skip the 'comment symbol
                     :raw (str/join "\n" (subvec content-lines
                                                 (dec (:line m))
                                                 (:end-line m)))})))))
    (catch Exception _ [])))

(defn- extract-comment-forms
  "Extract individual forms from inside a comment block with their text."
  [comment-block content]
  (let [forms (:forms comment-block)
        content-lines (vec (str/split-lines content))]
    (->> forms
         (filter #(meta %))
         (map (fn [form]
                (let [m (meta form)]
                  {:start-line (:line m)
                   :end-line (:end-line m)
                   :form (str/join "\n" (subvec content-lines
                                                (dec (:line m))
                                                (:end-line m)))}))))))

(defn eval-comment-block
  "Evaluate all forms inside a (comment ...) block."
  [{:keys [file line port]}]
  (if-let [validation-error (validate-file-exists file "file")]
    validation-error
    (try
      (let [;; Detect file type and find appropriate REPL
            detected-type (detect-repl-type file)
            effective-port (or port
                               (let [{:keys [success ports]} (discover-repls)]
                                 (when success
                                   (select-repl-for-file ports file))))
            content (slurp file)
            ns-name (extract-ns-from-content content)
            comment-blocks (find-comment-blocks content)
            ;; Find comment block containing or nearest to the given line
            target-block (or (first (filter #(and (<= (:start-line %) line)
                                                  (>= (:end-line %) line))
                                            comment-blocks))
                             ;; Find nearest block if not inside one
                             (first (sort-by #(Math/abs (- line (:start-line %)))
                                             comment-blocks)))]
        (if target-block
          (if effective-port
            (let [forms (extract-comment-forms target-block content)
                  ;; Switch to namespace first
                  _ (when ns-name
                      (eval-code {:code (str "(ns " ns-name ")") :port effective-port}))
                  ;; Evaluate each form
                  results (mapv (fn [form-info]
                                  (let [result (eval-code {:code (:form form-info)
                                                           :port effective-port})]
                                    {:lines [(:start-line form-info) (:end-line form-info)]
                                     :form (:form form-info)
                                     :success (:success result)
                                     :value (:value result)
                                     :error (:error result)}))
                                forms)]
              {:success true
               :file file
               :block-lines [(:start-line target-block) (:end-line target-block)]
               :namespace ns-name
               :repl-type (name detected-type)
               :port effective-port
               :forms-evaluated (count results)
               :results results})
            {:success false
             :error (str "No " (name detected-type) " REPL running. Start one with /clj-repl or in a terminal: "
                         (case detected-type
                           :clojure "clj -M:dev"
                           :clojurescript "npx shadow-cljs watch app"
                           :babashka "bb nrepl-server 1667"
                           "bb nrepl-server 1667"))})
          {:success false
           :error (str "No comment block found at or near line " line)}))
      (catch Exception e
        {:success false
         :error (str "Failed to eval comment block: " (.getMessage e))}))))

(defn- get-changed-clojure-files
  "Get list of changed Clojure files from git."
  []
  (try
    (let [;; Get staged and unstaged changes
          staged (p/shell {:out :string :continue true}
                          "git" "diff" "--cached" "--name-only")
          unstaged (p/shell {:out :string :continue true}
                            "git" "diff" "--name-only")
          all-files (str (:out staged) "\n" (:out unstaged))
          clj-pattern #"\.clj[csx]?$"]
      (->> (str/split-lines all-files)
           (filter #(re-find clj-pattern %))
           (filter #(not (str/blank? %)))
           (distinct)
           vec))
    (catch Exception _
      [])))

(defn validate-changed-files
  "Validate changed Clojure files by reloading namespaces and evaluating comment blocks.
   Returns a validation report with results for each file."
  [{:keys [files port]}]
  (try
    (let [;; Get files to validate
          target-files (if (seq files)
                         files
                         (get-changed-clojure-files))
          ;; Auto-discover port if needed
          effective-port (or port
                             (let [{:keys [success ports]} (discover-repls)]
                               (when success
                                 (first (extract-ports ports)))))]
      (if (empty? target-files)
        {:success true
         :files-validated 0
         :message "No changed Clojure files to validate"}
        (if effective-port
          (let [results
                (mapv
                 (fn [file]
                   (try
                     (let [content (slurp file)
                           ns-name (extract-ns-from-content content)
                           ;; Step 1: Reload namespace
                           reload-result (when ns-name
                                           (reload-namespace {:ns ns-name :port effective-port}))
                           ;; Step 2: Find and eval comment blocks
                           comment-blocks (find-comment-blocks content)
                           comment-results
                           (when (seq comment-blocks)
                             ;; Switch to namespace
                             (when ns-name
                               (eval-code {:code (str "(ns " ns-name ")") :port effective-port}))
                             ;; Eval each comment block
                             (mapv
                              (fn [block]
                                (let [forms (extract-comment-forms block content)
                                      form-results
                                      (mapv (fn [form-info]
                                              (let [result (eval-code {:code (:form form-info)
                                                                       :port effective-port})]
                                                {:lines [(:start-line form-info) (:end-line form-info)]
                                                 :success (:success result)
                                                 :value (:value result)
                                                 :error (:error result)}))
                                            forms)]
                                  {:block-lines [(:start-line block) (:end-line block)]
                                   :forms-evaluated (count form-results)
                                   :all-passed (every? :success form-results)
                                   :results form-results}))
                              comment-blocks))]
                       {:file file
                        :namespace ns-name
                        :reload-success (or (nil? reload-result) (:success reload-result))
                        :reload-error (:error reload-result)
                        :comment-blocks-count (count comment-blocks)
                        :comment-blocks comment-results
                        :all-passed (and (or (nil? reload-result) (:success reload-result))
                                         (every? :all-passed comment-results))})
                     (catch Exception e
                       {:file file
                        :error (str "Failed to validate: " (.getMessage e))
                        :all-passed false})))
                 target-files)]
            {:success true
             :port effective-port
             :files-validated (count results)
             :all-passed (every? :all-passed results)
             :summary {:total (count results)
                       :passed (count (filter :all-passed results))
                       :failed (count (filter #(not (:all-passed %)) results))}
             :results results})
          {:success false
           :error "No nREPL server running. Start one with /clj-repl or in a terminal: bb nrepl-server 1667"})))
    (catch Exception e
      {:success false
       :error (str "Failed to validate files: " (.getMessage e))})))

;; =============================================================================
;; Project Validation
;; =============================================================================

(defn- check-bb-override-builtin
  "Check if bb.edn has repl task without :override-builtin true."
  [path]
  (let [bb-file (java.io.File. path "bb.edn")]
    (when (.exists bb-file)
      (try
        (let [content (slurp bb-file)
              edn (read-string content)
              tasks (:tasks edn)]
          (when (and (contains? tasks 'repl)
                     (not (get-in tasks ['repl :override-builtin])))
            {:issue :bb-repl-override
             :message "bb.edn has 'repl' task without :override-builtin true"
             :fix-available true}))
        (catch Exception e
          {:issue :bb-parse-error
           :message (str "Failed to parse bb.edn: " (.getMessage e))})))))

;; Removed check-bb-tasks-use-clj and fix-bb-tasks-clj-to-clojure
;; We now use 'clj' everywhere for consistency with Clojure ecosystem docs

(defn- check-deps-resolve
  "Check if deps.edn dependencies resolve."
  [path]
  (let [deps-file (java.io.File. path "deps.edn")]
    (when (.exists deps-file)
      (try
        ;; Use 'clojure' not 'clj' since clj requires rlwrap
        (let [result (shell-execute {:dir path} "clojure" "-Spath")]
          (when-not (zero? (:exit result))
            {:issue :deps-resolve-failed
             :message (str "deps.edn failed to resolve: " (str/trim (:err result)))
             :output (:err result)}))
        (catch Exception e
          {:issue :deps-check-error
           :message (str "Failed to check deps: " (.getMessage e))})))))

(defn- check-npm-install
  "Check if package.json exists but node_modules doesn't."
  [path]
  (let [pkg-file (java.io.File. path "package.json")
        node-modules (java.io.File. path "node_modules")]
    (when (and (.exists pkg-file) (not (.exists node-modules)))
      {:issue :npm-not-installed
       :message "package.json exists but node_modules missing - run 'npm install'"
       :fix-available true})))

(defn- is-clojuredart-project?
  "Check if project has :cljd/opts in deps.edn."
  [path]
  (let [deps-file (java.io.File. path "deps.edn")]
    (when (.exists deps-file)
      (try
        (let [deps (edn/read-string (slurp deps-file))]
          (contains? deps :cljd/opts))
        (catch Exception _ false)))))

(defn- check-cljd-init
  "Check if ClojureDart project needs initialization."
  [path]
  (when (is-clojuredart-project? path)
    (let [pubspec (java.io.File. path "pubspec.yaml")]
      (when-not (.exists pubspec)
        {:issue :cljd-not-initialized
         :message "ClojureDart project not initialized - running 'clojure -M:cljd init'"
         :fix-available true}))))

(defn- run-cljd-init
  "Run ClojureDart initialization."
  [path]
  (try
    (println "Initializing ClojureDart project...")
    (let [result (shell-execute {:dir path} "clojure" "-M:cljd" "init")]
      (if (zero? (:exit result))
        {:fixed true :message "ClojureDart initialized successfully"}
        {:fixed false :message (str "ClojureDart init failed: " (:err result))}))
    (catch Exception e
      {:fixed false :message (str "ClojureDart init failed: " (.getMessage e))})))

(defn- mise-available?
  "Check if mise is installed."
  []
  (try
    (let [result (shell-execute "which" "mise")]
      (zero? (:exit result)))
    (catch Exception _ false)))

(defn- get-java-version
  "Get the current Java major version."
  [path]
  (try
    (let [result (shell-execute {:dir path} "java" "-version")
          version-str (or (:err result) (:out result))
          version-match (re-find #"version \"(\d+)" version-str)]
      (when version-match (parse-long (second version-match))))
    (catch Exception _ nil)))

(defn- get-shadow-cljs-major-version
  "Get the major version of shadow-cljs from package.json (2 or 3)."
  [path]
  (let [package-json (java.io.File. path "package.json")]
    (when (.exists package-json)
      (try
        (let [content (slurp package-json)]
          (cond
            (re-find #"\"shadow-cljs\":\s*\"[\^~]?3\." content) 3
            (re-find #"\"shadow-cljs\":\s*\"[\^~]?2\." content) 2
            :else nil))
        (catch Exception _ nil)))))

(defn- check-java-version
  "Check Java version meets minimum for shadow-cljs.
   shadow-cljs 2.x requires Java 11+, 3.x requires Java 21+."
  [path]
  (let [shadow-file (java.io.File. path "shadow-cljs.edn")]
    (when (.exists shadow-file)
      (try
        (let [shadow-major (get-shadow-cljs-major-version path)
              min-version (case shadow-major
                            3 21
                            2 11
                            21) ;; default to 21 if unknown
              major-version (get-java-version path)]
          (when (and major-version (< major-version min-version))
            {:issue :java-version-low
             :message (str "Java " major-version " detected — shadow-cljs "
                           (or shadow-major "3") ".x requires Java " min-version "+")
             :current-version major-version
             :required-version min-version
             :fix-available (mise-available?)}))
        (catch Exception e
          {:issue :java-check-error
           :message (str "Failed to check Java version: " (.getMessage e))})))))

(defn- check-shadow-cljs-upgrade
  "Suggest shadow-cljs 3.x upgrade if Java 21+ is available."
  [path]
  (let [package-json (java.io.File. path "package.json")]
    (when (.exists package-json)
      (try
        (let [content (slurp package-json)
              ;; Check if using shadow-cljs 2.x
              shadow-2x? (re-find #"\"shadow-cljs\":\s*\"[\^~]?2\." content)
              java-version (get-java-version path)]
          (when (and shadow-2x? java-version (>= java-version 21))
            {:issue :shadow-cljs-upgrade-available
             :message (str "Java " java-version " detected. Consider upgrading shadow-cljs to 3.x for ESM support, smaller bundles, and better npm resolution. Update package.json: \"shadow-cljs\": \"^3.3.5\"")
             :java-version java-version
             :fix-available false}))
        (catch Exception _ nil)))))

(defn- check-rlwrap-installed
  "Check if rlwrap is installed (needed for clj command readline support)."
  [_path]
  (try
    (let [result (shell-execute "which" "rlwrap")]
      (when-not (zero? (:exit result))
        {:issue :rlwrap-not-installed
         :message "rlwrap not installed - 'clj' command will work but without readline support. Install: sudo pacman -S rlwrap (Arch) or brew install rlwrap (macOS)"
         :fix-available false}))
    (catch Exception _
      {:issue :rlwrap-not-installed
       :message "rlwrap not installed - 'clj' command will work but without readline support"
       :fix-available false})))

(defn- fix-bb-override-builtin
  "Add :override-builtin true to repl task in bb.edn."
  [path]
  (let [bb-file (java.io.File. path "bb.edn")]
    (try
      (let [content (slurp bb-file)
            ;; Insert :override-builtin true after the opening brace of repl task
            ;; Match: repl {... :task
            ;; Replace with: repl {:override-builtin true ... :task
            fixed (str/replace content
                               #"(repl\s+\{)([^}]*?)(:task)"
                               "$1:override-builtin true\n        $2$3")]
        (if (= content fixed)
          {:fix-failed :bb-repl-override
           :message "Could not find repl task pattern to fix"}
          (do
            (spit bb-file fixed)
            {:fixed :bb-repl-override
             :message "Added :override-builtin true to repl task"})))
      (catch Exception e
        {:fix-failed :bb-repl-override
         :message (str "Failed to fix: " (.getMessage e))}))))

(defn- run-npm-install
  "Run npm install in the project directory."
  [path]
  (try
    (let [result (shell-execute {:dir path} "npm" "install")]
      (if (zero? (:exit result))
        {:fixed :npm-install
         :message "Ran npm install successfully"}
        {:fix-failed :npm-install
         :message (str "npm install failed: " (:err result))}))
    (catch Exception e
      {:fix-failed :npm-install
       :message (str "Failed to run npm install: " (.getMessage e))})))

(defn validate-project
  "Validate a Clojure project setup. Checks common issues after scaffolding."
  [{:keys [path fix] :or {path "." fix false}}]
  (try
    (let [;; Run all checks
          checks [(check-bb-override-builtin path)
                  (check-deps-resolve path)
                  (check-npm-install path)
                  (check-cljd-init path)
                  (check-java-version path)
                  (check-shadow-cljs-upgrade path)
                  (check-rlwrap-installed path)]
          issues (remove nil? checks)

          ;; Apply fixes if requested
          fixes (when fix
                  (remove nil?
                          [(when (some #(= (:issue %) :bb-repl-override) issues)
                             (fix-bb-override-builtin path))
                           (when (some #(= (:issue %) :npm-not-installed) issues)
                             (run-npm-install path))
                           (when (some #(= (:issue %) :cljd-not-initialized) issues)
                             (run-cljd-init path))]))

          ;; Re-check issues after fixes to update status
          ;; Java version is informational only (doesn't block success)
          remaining-issues (if fix
                             (remove nil?
                                     [(check-bb-override-builtin path)
                                      (check-deps-resolve path)
                                      (check-npm-install path)
                                      (check-cljd-init path)])
                             ;; Filter out informational issues for success check
                             (remove #(#{:java-version-low :rlwrap-not-installed :shadow-cljs-upgrade-available} (:issue %)) issues))
          ;; Keep informational issues for display, but don't let them block success
          info-issues (filter #(#{:java-version-low :rlwrap-not-installed :shadow-cljs-upgrade-available} (:issue %)) issues)
          summary (cond
                    (seq remaining-issues)
                    (str (count remaining-issues) " issue(s) found"
                         (when (seq fixes)
                           (str ", " (count (filter :fixed fixes)) " fixed")))
                    (seq info-issues)
                    (str "All checks passed (info: " (str/join ", " (map :message info-issues)) ")")
                    :else "All checks passed")]
      {:success (empty? remaining-issues)
       :path path
       :issues (vec issues)
       :fixes (vec fixes)
       :info (vec info-issues)
       :remaining-issues (when fix (vec remaining-issues))
       :summary summary
         ;; Server expects :error when :success is false
       :error (when (seq remaining-issues)
                (str summary ": "
                     (str/join ", " (map :message remaining-issues))))})
    (catch Exception e
      {:success false
       :error (str "Validation failed: " (.getMessage e))})))

(defn- tail-log-file
  "Read the last N lines from a log file."
  [file lines]
  (try
    (when (.exists file)
      (let [content (slurp file)
            all-lines (str/split-lines content)
            line-count (count all-lines)
            start-idx (max 0 (- line-count lines))]
        (str/join "\n" (subvec (vec all-lines) start-idx))))
    (catch Exception _ nil)))

(defn view-repl-logs
  "View REPL process logs from .forj/logs/."
  [{:keys [path log lines] :or {path "." log "all" lines 50}}]
  (try
    (let [logs-dir (java.io.File. path ".forj/logs")
          log-files {:backend (java.io.File. logs-dir "backend.log")
                     :shadow (java.io.File. logs-dir "shadow.log")
                     :expo (java.io.File. logs-dir "expo.log")}
          selected (if (= log "all")
                     [:backend :shadow :expo]
                     [(keyword log)])]
      (if (.exists logs-dir)
        (let [results (->> selected
                           (map (fn [k]
                                  (let [file (get log-files k)
                                        content (tail-log-file file lines)]
                                    (when content
                                      {:log (name k)
                                       :file (.getPath file)
                                       :lines (count (str/split-lines content))
                                       :content content}))))
                           (remove nil?))]
          (if (seq results)
            {:success true
             :logs results}
            {:success true
             :logs []
             :message "No log files found. REPLs may not have been started with tee logging."}))
        {:success false
         :error (str "Log directory not found: " (.getPath logs-dir) ". Start REPLs with /clj-repl to enable logging.")}))
    (catch Exception e
      {:success false
       :error (str "Failed to read logs: " (.getMessage e))})))

;; =============================================================================
;; Process Tracking (for /clj-repl stop)
;; =============================================================================

(defn- session-file-path
  "Get the session file path for a project directory.
   Uses a hash of the absolute path to create a unique filename."
  [project-path]
  (let [abs-path (str (fs/absolutize project-path))
        hash-str (format "%08x" (hash abs-path))
        sessions-dir (str (fs/path (System/getProperty "user.home") ".forj" "sessions"))]
    (fs/create-dirs sessions-dir)
    (str (fs/path sessions-dir (str hash-str ".edn")))))

(defn- read-session
  "Read session data for a project."
  [project-path]
  (let [file (session-file-path project-path)]
    (when (fs/exists? file)
      (try
        (edn/read-string (slurp file))
        (catch Exception _ nil)))))

(defn- write-session
  "Write session data for a project."
  [project-path data]
  (let [file (session-file-path project-path)]
    (spit file (pr-str data))))

(defn- process-alive?
  "Check if a process with given PID is still running."
  [pid]
  (try
    (let [result (shell-execute "kill" "-0" (str pid))]
      (zero? (:exit result)))
    (catch Exception _ false)))

(defn- get-pgid
  "Get the process group ID (PGID) for a given PID."
  [pid]
  (try
    (let [result (shell-execute "ps" "-o" "pgid=" "-p" (str pid))]
      (when (zero? (:exit result))
        (parse-long (str/trim (:out result)))))
    (catch Exception _ nil)))

(defn track-process
  "Track a process for later cleanup.
   SAFETY: Refuses to track PID 0 or negative PIDs - kill 0 sends signals to entire process group."
  [{:keys [pid name port command]}]
  (cond
    ;; Safety check: PID 0 is dangerous (kills process group), negative PIDs are invalid
    (or (nil? pid) (not (pos-int? pid)))
    {:success false
     :error (str "Invalid PID: " pid " - must be a positive integer. "
                 "PID 0 would kill the entire process group!")}

    :else
    (try
      (let [path "."
            session (or (read-session path) {:processes [] :path (str (fs/absolutize path))})
            pgid (get-pgid pid)
            process-entry (cond-> {:pid pid
                                   :name name
                                   :port port
                                   :command command
                                   :started-at (str (java.time.Instant/now))}
                            pgid (assoc :pgid pgid))
            ;; Remove any existing entry with same name (replacing)
            existing (remove #(= (:name %) name) (:processes session))
            updated (assoc session :processes (conj (vec existing) process-entry))]
        (write-session path updated)
        {:success true
         :message (str "Tracking process '" name "' (PID " pid (when pgid (str ", PGID " pgid)) ")")
         :tracked process-entry})
      (catch Exception e
        {:success false
         :error (str "Failed to track process: " (.getMessage e))}))))

(defn list-tracked-processes
  "List all tracked processes for a project.
   Auto-prunes dead processes from the session file and returns :pruned-count."
  [{:keys [path] :or {path "."}}]
  (try
    (if-let [session (read-session path)]
      (let [processes (:processes session)
            ;; Check which are still alive
            with-status (mapv (fn [p]
                                (assoc p :alive (process-alive? (:pid p))))
                              processes)
            alive-processes (filterv :alive with-status)
            dead-processes (filterv (complement :alive) with-status)
            pruned-count (count dead-processes)]
        ;; Auto-prune: Update session file to only contain alive processes
        (when (pos? pruned-count)
          (let [alive-entries (mapv #(dissoc % :alive) alive-processes)]
            (if (empty? alive-entries)
              ;; Delete session file if no processes remain
              (let [session-file (session-file-path path)]
                (when (fs/exists? session-file)
                  (fs/delete session-file)))
              ;; Update session with only alive processes
              (write-session path (assoc session :processes alive-entries)))))
        {:success true
         :project-path (:path session)
         :processes with-status
         :count (count processes)
         :alive-count (count alive-processes)
         :pruned-count pruned-count
         :pruned (when (pos? pruned-count)
                   (mapv #(select-keys % [:name :pid :pgid]) dead-processes))})
      {:success true
       :processes []
       :count 0
       :pruned-count 0
       :message "No tracked processes for this project"})
    (catch Exception e
      {:success false
       :error (str "Failed to list processes: " (.getMessage e))})))

(defn stop-project
  "Stop all tracked processes for a project.
   Uses process group kill (PGID) when available, falls back to individual PID.
   SAFETY: Refuses to kill PID 0 or negative PIDs, and validates PGID before use."
  [{:keys [path] :or {path "."}}]
  (try
    (if-let [session (read-session path)]
      (let [processes (:processes session)
            results (mapv (fn [{:keys [pid pgid name]}]
                            (cond
                              ;; SAFETY: Never kill PID 0 (kills process group) or invalid PIDs
                              (or (nil? pid) (not (pos-int? pid)))
                              {:name name :pid pid :pgid pgid :stopped false
                               :error "SAFETY: Refused to kill invalid PID (0 or negative)"}

                              (process-alive? pid)
                              (try
                                ;; Prefer killing by process group (PGID) to get all children
                                ;; Use negative PGID with kill to signal entire group
                                (if (and pgid (pos-int? pgid))
                                  ;; Kill entire process group using negative PGID
                                  (let [result (shell-execute "kill" "-TERM" (str "-" pgid))]
                                    (if (zero? (:exit result))
                                      {:name name :pid pid :pgid pgid :stopped true :killed-by :pgid}
                                      ;; If group kill fails, fall back to individual PID
                                      (let [pid-result (shell-execute "kill" "-TERM" (str pid))]
                                        (if (zero? (:exit pid-result))
                                          {:name name :pid pid :pgid pgid :stopped true :killed-by :pid-fallback}
                                          {:name name :pid pid :pgid pgid :stopped false :error (:err pid-result)}))))
                                  ;; No PGID available (legacy entry), kill by PID
                                  (let [result (shell-execute "kill" "-TERM" (str pid))]
                                    (if (zero? (:exit result))
                                      {:name name :pid pid :stopped true :killed-by :pid}
                                      {:name name :pid pid :stopped false :error (:err result)})))
                                (catch Exception e
                                  {:name name :pid pid :pgid pgid :stopped false :error (.getMessage e)}))

                              :else
                              {:name name :pid pid :pgid pgid :stopped false :already-dead true}))
                          processes)
            stopped-count (count (filter :stopped results))
            ;; Clear the session file
            session-file (session-file-path path)]
        (when (fs/exists? session-file)
          (fs/delete session-file))
        {:success true
         :stopped-count stopped-count
         :results results
         :message (str "Stopped " stopped-count " of " (count processes) " processes")})
      {:success true
       :stopped-count 0
       :message "No tracked processes to stop"})
    (catch Exception e
      {:success false
       :error (str "Failed to stop processes: " (.getMessage e))})))

;; =============================================================================
;; Lisa Loop v2 Tools (Planning + Orchestration)
;; =============================================================================


(defn- parse-checkpoint-id
  "Parse checkpoint ID from string - could be a number or keyword name."
  [checkpoint-str]
  (if-let [n (parse-long (str checkpoint-str))]
    n
    (keyword (str/replace (str checkpoint-str) #"^:" ""))))

(defn lisa-create-plan
  "Create a LISA_PLAN.edn with checkpoints (supports dependencies)."
  [{:keys [title checkpoints path] :or {path "."}}]
  (try
    (let [checkpoint-data (mapv (fn [cp]
                                  (cond-> {:description (:description cp)}
                                    (:id cp) (assoc :id (keyword (:id cp)))
                                    (:file cp) (assoc :file (:file cp))
                                    (:acceptance cp) (assoc :acceptance (:acceptance cp))
                                    (:gates cp) (assoc :gates (vec (:gates cp)))
                                    (:depends_on cp) (assoc :depends-on (mapv keyword (:depends_on cp)))))
                                checkpoints)]
      (plan-edn/create-plan! path {:title title :checkpoints checkpoint-data})
      {:success true
       :message (str "Created LISA_PLAN.edn with " (count checkpoints) " checkpoints")
       :path (str (fs/path path "LISA_PLAN.edn"))})
    (catch Exception e
      {:success false
       :error (str "Failed to create plan: " (.getMessage e))})))

(defn lisa-get-plan
  "Read the current plan (EDN preferred, markdown fallback).
   Returns compressed context optimized for Claude iterations."
  [{:keys [path max_signs full] :or {path "." max_signs 5 full false}}]
  (try
    (cond
      ;; EDN format (preferred)
      (plan-edn/plan-exists? path)
      (let [plan (plan-edn/read-plan path)]
        (if full
          {:success true
           :format :edn
           :plan plan}
          {:success true
           :format :edn
           :context (plan-edn/context-for-iteration plan {:max-signs max_signs})
           :current-checkpoint (plan-edn/current-checkpoint plan)
           :all-complete (plan-edn/all-complete? plan)}))

      ;; Markdown fallback
      (lisa-plan/plan-exists? path)
      (let [plan (lisa-plan/parse-plan path)]
        {:success true
         :format :markdown
         :plan plan
         :current-checkpoint (lisa-plan/current-checkpoint plan)
         :all-complete (lisa-plan/all-complete? plan)})

      :else
      {:success false
       :error "No LISA_PLAN.edn or LISA_PLAN.md found"})
    (catch Exception e
      {:success false
       :error (str "Failed to read plan: " (.getMessage e))})))

(defn lisa-mark-checkpoint-done
  "Mark a checkpoint as done (supports both EDN and markdown formats)."
  [{:keys [checkpoint path] :or {path "."}}]
  (try
    (cond
      ;; EDN format
      (plan-edn/plan-exists? path)
      (let [cp-id (parse-checkpoint-id checkpoint)
            ;; If given a number, find the checkpoint ID
            plan (plan-edn/read-plan path)
            actual-id (if (number? cp-id)
                        (:id (plan-edn/checkpoint-by-number plan cp-id))
                        cp-id)]
        (if-let [updated-plan (plan-edn/mark-checkpoint-done! path actual-id)]
          (let [next-cp (plan-edn/current-checkpoint updated-plan)
                ready (plan-edn/ready-checkpoints updated-plan)]
            {:success true
             :message (str "Marked checkpoint " actual-id " as done")
             :all-complete (plan-edn/all-complete? updated-plan)
             :next-checkpoint next-cp
             :ready-checkpoints (mapv :id ready)})
          {:success false
           :error "Failed to update plan - checkpoint may not exist"}))

      ;; Markdown fallback
      (lisa-plan/plan-exists? path)
      (let [cp-num (if (number? (parse-checkpoint-id checkpoint))
                     (parse-checkpoint-id checkpoint)
                     (throw (ex-info "Markdown plans require numeric checkpoint IDs" {})))]
        (if-let [updated-plan (lisa-plan/mark-checkpoint-done! path cp-num)]
          {:success true
           :message (str "Marked checkpoint " cp-num " as done")
           :all-complete (lisa-plan/all-complete? updated-plan)
           :next-checkpoint (lisa-plan/current-checkpoint updated-plan)}
          {:success false
           :error "Failed to update plan - plan may not exist"}))

      :else
      {:success false
       :error "No LISA_PLAN.edn or LISA_PLAN.md found"})
    (catch Exception e
      {:success false
       :error (str "Failed to mark checkpoint: " (.getMessage e))})))

(defn lisa-add-checkpoint
  "Add a checkpoint to a running Lisa plan.
   Position is auto-determined by default:
   - If depends_on specified → insert after last dependency
   - If loop is running → insert after current in-progress checkpoint
   - Otherwise → append to end

   Explicit position options: 'end', 'next', or a checkpoint ID."
  [{:keys [id description file acceptance gates depends_on position path]
    :or {path "."}}]
  (try
    (if-not (plan-edn/plan-exists? path)
      {:success false
       :error "No LISA_PLAN.edn found. Create a plan first with lisa_create_plan."}
      (let [checkpoint (cond-> {:id (keyword id)
                                :description description}
                         file (assoc :file file)
                         acceptance (assoc :acceptance acceptance)
                         gates (assoc :gates (vec gates))
                         depends_on (assoc :depends-on (mapv keyword depends_on)))
            pos-kw (cond
                     (nil? position) :auto
                     (= position "end") :end
                     (= position "next") :next
                     (= position "auto") :auto
                     :else (keyword position))
            updated-plan (plan-edn/add-checkpoint! path checkpoint :position pos-kw)]
        (if updated-plan
          (let [new-cp (plan-edn/checkpoint-by-id updated-plan (keyword id))
                idx (inc (.indexOf (mapv :id (:checkpoints updated-plan)) (keyword id)))]
            {:success true
             :message (str "Added checkpoint :" id " at position " idx)
             :checkpoint new-cp
             :total-checkpoints (count (:checkpoints updated-plan))
             :position idx
             :position-reason (cond
                                (seq depends_on) "after last dependency"
                                (some #(= :in-progress (:status %)) (:checkpoints updated-plan)) "after current in-progress"
                                :else "appended to end")})
          {:success false
           :error "Failed to add checkpoint to plan"})))
    (catch Exception e
      {:success false
       :error (str "Failed to add checkpoint: " (.getMessage e))})))

(defn lisa-run-orchestrator
  "Run the Lisa Loop orchestrator (spawns Claude instances).
   Spawns as a detached background process and returns immediately."
  [{:keys [path max_iterations parallel max_parallel verbose
           iteration_timeout idle_timeout no_timeout]
    :or {path "." max_iterations 20 parallel true max_parallel 3 verbose false
         iteration_timeout nil idle_timeout 300 no_timeout false}}]
  (try
    (let [project-path (fs/absolutize path)
          log-dir (str (fs/path project-path ".forj/logs/lisa"))
          _ (fs/create-dirs log-dir)
          timestamp (-> (java.time.LocalDateTime/now)
                        (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))
          log-file (str (fs/path log-dir (str "orchestrator-" timestamp ".log")))

          ;; Build command args (parallel is default, use --sequential to disable)
          cmd-args (cond-> ["bb" "-cp" (System/getProperty "java.class.path")
                            "-m" "forj.lisa.orchestrator" (str project-path)
                            "--max-iterations" (str max_iterations)]
                     (not parallel) (into ["--sequential"])
                     (and parallel max_parallel) (into ["--max-parallel" (str max_parallel)])
                     verbose (into ["--verbose"])
                     no_timeout (into ["--no-timeout"])
                     (and (not no_timeout) iteration_timeout) (into ["--iteration-timeout" (str iteration_timeout)])
                     (and (not no_timeout) idle_timeout) (into ["--idle-timeout" (str idle_timeout)]))

          ;; Spawn detached process with output redirected to log file
          proc (apply p/process {:dir (str project-path)
                                 :out (java.io.File. log-file)
                                 :err :out}  ;; stderr to same file
                      cmd-args)
          pid (-> proc :proc .pid)]

      {:success true
       :message (cond
                  (and parallel verbose) (str "Lisa Loop orchestrator started (parallel mode, max " max_parallel " concurrent, verbose)")
                  parallel (str "Lisa Loop orchestrator started (parallel mode, max " max_parallel " concurrent)")
                  verbose "Lisa Loop orchestrator started (sequential mode, verbose)"
                  :else "Lisa Loop orchestrator started (sequential mode)")
       :pid pid
       :log-file log-file
       :parallel parallel
       :verbose verbose
       :timeouts (if no_timeout
                   :disabled
                   {:iteration-sec iteration_timeout :idle-sec idle_timeout})
       :monitor-hint (str "tail -f " log-file)})
    (catch Exception e
      {:success false
       :error (str "Failed to start orchestrator: " (.getMessage e))})))

(defn repl-snapshot
  "Take a snapshot of REPL state."
  [{:keys [port namespace]}]
  (try
    ;; Get all loaded namespaces
    (let [ns-code "(mapv (comp str ns-name) (all-ns))"
          ns-result (eval-code {:code ns-code :port port :timeout 5000})

          ;; Get namespace publics if specified
          vars-result (when namespace
                        (eval-code
                         {:code (str "(mapv (fn [[k v]] {:name (str k) :type (str (type @v))}) "
                                     "(ns-publics '" namespace "))")
                          :port port
                          :timeout 5000}))

          ;; Check for common server state vars
          server-check (eval-code
                        {:code "(vec (filter some? [(when (resolve 'user/system) {:user/system @(resolve 'user/system)})
                                        (when (resolve 'mount.core/running-states) {:mount/running @(resolve 'mount.core/running-states)})]))"
                         :port port
                         :timeout 5000})]

      (if (:success ns-result)
        {:success true
         :namespaces (when-let [v (:value ns-result)] (edn/read-string v))
         :vars (when (and vars-result (:success vars-result))
                 (when-let [v (:value vars-result)] (edn/read-string v)))
         :server-state (when (:success server-check)
                         (when-let [v (:value server-check)] (edn/read-string v)))}
        {:success false
         :error (or (:error ns-result) "Failed to query REPL")}))
    (catch Exception e
      {:success false
       :error (str "Failed to snapshot REPL: " (.getMessage e))})))

;; =============================================================================
;; Signs (Guardrails) Tools
;; =============================================================================

(defn lisa-append-sign
  "Append a sign (learning). Uses EDN embedded signs if LISA_PLAN.edn exists, otherwise LISA_SIGNS.md."
  [{:keys [iteration checkpoint issue fix severity path] :or {path "." severity "error"}}]
  (try
    (if (plan-edn/plan-exists? path)
      ;; EDN format - embedded signs
      (let [cp-key (if (string? checkpoint) (keyword checkpoint) checkpoint)]
        (plan-edn/append-sign! path {:iteration iteration
                                     :checkpoint cp-key
                                     :issue issue
                                     :fix fix
                                     :severity (keyword severity)})
        {:success true
         :format :edn
         :message "Appended sign to LISA_PLAN.edn"})
      ;; Markdown fallback
      (let [sign (lisa-signs/append-sign! path
                                          {:iteration iteration
                                           :checkpoint checkpoint
                                           :issue issue
                                           :fix fix
                                           :severity (keyword severity)})]
        {:success true
         :format :markdown
         :message (str "Appended sign " (:number sign) " to LISA_SIGNS.md")
         :sign sign}))
    (catch Exception e
      {:success false
       :error (str "Failed to append sign: " (.getMessage e))})))

(defn lisa-get-signs
  "Read signs. Uses EDN embedded signs if LISA_PLAN.edn exists, otherwise LISA_SIGNS.md."
  [{:keys [path max_signs] :or {path "." max_signs 10}}]
  (try
    (cond
      ;; EDN format - embedded signs
      (plan-edn/plan-exists? path)
      (let [plan (plan-edn/read-plan path)
            signs (plan-edn/recent-signs plan max_signs)]
        {:success true
         :format :edn
         :signs signs
         :count (count signs)})

      ;; Markdown fallback
      (lisa-signs/signs-exist? path)
      {:success true
       :format :markdown
       :signs (lisa-signs/signs-summary path)
       :raw (lisa-signs/read-signs path)}

      :else
      {:success true
       :signs nil
       :message "No signs found"})
    (catch Exception e
      {:success false
       :error (str "Failed to read signs: " (.getMessage e))})))

(defn lisa-clear-signs
  "Clear signs. For EDN, clears embedded signs. For markdown, deletes LISA_SIGNS.md."
  [{:keys [path] :or {path "."}}]
  (try
    (if (plan-edn/plan-exists? path)
      ;; EDN format - clear embedded signs
      (do
        (plan-edn/prune-old-signs! path 0)  ;; Keep 0 iterations = clear all
        {:success true
         :format :edn
         :message "Cleared signs from LISA_PLAN.edn"})
      ;; Markdown fallback
      (do
        (lisa-signs/clear-signs! path)
        {:success true
         :format :markdown
         :message "Cleared LISA_SIGNS.md"}))
    (catch Exception e
      {:success false
       :error (str "Failed to clear signs: " (.getMessage e))})))

;; =============================================================================
;; Validation Tools
;; =============================================================================

(defn lisa-run-validation
  "Run validation checks."
  [{:keys [validation port]}]
  (try
    (let [opts {:port port}
          result (lisa-validation/run-validations validation opts)]
      {:success true
       :all-passed (:all-passed result)
       :summary (:summary result)
       :results (:results result)})
    (catch Exception e
      {:success false
       :error (str "Failed to run validation: " (.getMessage e))})))

(defn lisa-check-gates
  "Check if all gates have passed."
  [{:keys [gates port]}]
  (try
    (let [opts {:port port}
          result (lisa-validation/checkpoint-gates-passed? gates opts)]
      {:success true
       :gates-passed (:passed result)
       :message (:message result)})
    (catch Exception e
      {:success false
       :error (str "Failed to check gates: " (.getMessage e))})))

(defn- last-lisa-activity
  "Get the most recent modification time of Lisa-related files.
   Checks (in priority order):
   1. LISA_PLAN.edn - updated on every checkpoint state change
   2. Orchestrator log - updated continuously during execution
   3. Source files - updated when agents edit code"
  [project-path]
  (try
    (let [plan-file (str project-path "/LISA_PLAN.edn")
          log-dir (str project-path "/.forj/logs/lisa")
          ;; Get plan file modification time (most reliable)
          plan-mod (when (fs/exists? plan-file)
                     (.toMillis (fs/last-modified-time plan-file)))
          ;; Get latest orchestrator log modification time
          orch-logs (when (fs/exists? log-dir)
                      (fs/glob log-dir "orchestrator-*.log"))
          latest-orch-log (when (seq orch-logs)
                            (last (sort-by #(.toMillis (fs/last-modified-time %)) orch-logs)))
          orch-mod (when latest-orch-log
                     (.toMillis (fs/last-modified-time latest-orch-log)))
          ;; Get latest iteration log modification time
          iter-logs (when (fs/exists? log-dir)
                      (fs/glob log-dir "*.json"))
          latest-iter-log (when (seq iter-logs)
                            (last (sort-by #(.toMillis (fs/last-modified-time %)) iter-logs)))
          iter-mod (when latest-iter-log
                     (.toMillis (fs/last-modified-time latest-iter-log)))
          ;; Get source file modification times
          src-files (concat
                     (fs/glob (str project-path "/src") "**/*.{clj,cljs,cljc}")
                     (fs/glob (str project-path "/test") "**/*.{clj,cljs,cljc}"))
          src-mod (when (seq src-files)
                    (->> src-files
                         (map #(.toMillis (fs/last-modified-time %)))
                         (apply max)))
          ;; Return the most recent of all
          all-mods (remove nil? [plan-mod orch-mod iter-mod src-mod])]
      (if (seq all-mods)
        (apply max all-mods)
        0))
    (catch Exception _ 0)))

(defn- format-elapsed [ms]
  (when ms
    (let [seconds (quot ms 1000)
          minutes (quot seconds 60)
          hours (quot minutes 60)]
      (cond
        (>= hours 1) (format "%dh %dm" hours (mod minutes 60))
        (>= minutes 1) (format "%dm %ds" minutes (mod seconds 60))
        :else (format "%ds" seconds)))))

(defn lisa-watch
  "Get Lisa Loop status for monitoring."
  [{:keys [path] :or {path "."}}]
  (try
    (let [project-path (str (fs/absolutize path))
          ;; Check for plan file
          edn-exists? (fs/exists? (str project-path "/LISA_PLAN.edn"))
          md-exists? (fs/exists? (str project-path "/LISA_PLAN.md"))
          now (System/currentTimeMillis)]
      (cond
        ;; No plan found
        (not (or edn-exists? md-exists?))
        {:success true
         :status :no-plan
         :message "No LISA_PLAN.edn or LISA_PLAN.md found"}

        ;; EDN format - full structured data
        edn-exists?
        (let [plan (plan-edn/read-plan project-path)
              checkpoints (:checkpoints plan)
              current (plan-edn/current-checkpoint plan)
              done-count (count (filter #(= :done (:status %)) checkpoints))
              total-count (count checkpoints)
              last-file-mod (last-lisa-activity project-path)
              idle-ms (when (pos? last-file-mod) (- now last-file-mod))
              ;; Check log dir for iteration info
              log-dir (str project-path "/.forj/logs/lisa")
              iter-files (when (fs/exists? log-dir)
                           (fs/glob log-dir "iter-*.json"))
              ;; Get meta files for session-id extraction
              meta-files (when (fs/exists? log-dir)
                           (fs/glob log-dir "iter-*-meta.json"))
              iteration-count (count iter-files)
              ;; Get cost from latest iteration log
              latest-log (when (seq iter-files)
                           (last (sort iter-files)))
              latest-result (when latest-log
                              (try
                                (json/parse-string (slurp (str latest-log)) true)
                                (catch Exception _ nil)))
              ;; Get session-id from latest meta file for tool usage
              latest-meta (when (seq meta-files)
                            (last (sort meta-files)))
              latest-meta-data (when latest-meta
                                 (try
                                   (json/parse-string (slurp (str latest-meta)) true)
                                   (catch Exception _ nil)))
              session-id (:session-id latest-meta-data)
              ;; Get tool usage from Claude session logs
              tool-summary (when session-id
                             (try
                               (claude-sessions/session-tool-summary session-id project-path)
                               (catch Exception _ nil)))]
          {:success true
           :status (:status plan)
           :title (:title plan)
           :progress {:done done-count
                      :total total-count
                      :percent (when (pos? total-count)
                                 (int (* 100 (/ done-count total-count))))}
           :current-checkpoint (when current
                                 {:id (:id current)
                                  :description (:description current)
                                  :file (:file current)
                                  :started (:started current)})
           :checkpoints (mapv (fn [cp]
                                {:id (:id cp)
                                 :status (:status cp)
                                 :description (:description cp)})
                              checkpoints)
           :iterations iteration-count
           :last-cost (when latest-result (:total_cost_usd latest-result))
           :file-activity {:last-modified-ms idle-ms
                           :last-modified-ago (format-elapsed idle-ms)
                           :possibly-stuck (when idle-ms (> idle-ms (* 5 60 1000)))}
           :signs (take 3 (reverse (:signs plan)))
           ;; Tool usage from Claude session logs (current/last iteration)
           :tool-usage (when (and tool-summary (:exists? tool-summary))
                         {:session-id session-id
                          :total-calls (:total-calls tool-summary)
                          :tool-counts (:tool-counts tool-summary)})})

        ;; Markdown format - basic info
        :else
        (let [plan (lisa-plan/parse-plan (slurp (str project-path "/LISA_PLAN.md")))
              checkpoints (:checkpoints plan)
              current (first (filter #(= :in-progress (:status %)) checkpoints))
              done-count (count (filter #(= :done (:status %)) checkpoints))
              total-count (count checkpoints)
              last-file-mod (last-lisa-activity project-path)
              idle-ms (when (pos? last-file-mod) (- now last-file-mod))]
          {:success true
           :status (cond
                     (every? #(= :done (:status %)) checkpoints) :complete
                     (some #(= :in-progress (:status %)) checkpoints) :in-progress
                     :else :pending)
           :title (:title plan)
           :progress {:done done-count
                      :total total-count
                      :percent (when (pos? total-count)
                                 (int (* 100 (/ done-count total-count))))}
           :current-checkpoint (when current
                                 {:number (:number current)
                                  :description (:description current)
                                  :file (:file current)})
           :checkpoints (mapv (fn [cp]
                                {:number (:number cp)
                                 :status (:status cp)
                                 :description (:description cp)})
                              checkpoints)
           :file-activity {:last-modified-ms idle-ms
                           :last-modified-ago (format-elapsed idle-ms)
                           :possibly-stuck (when idle-ms (> idle-ms (* 5 60 1000)))}})))
    (catch Exception e
      {:success false
       :error (str "Failed to get loop status: " (.getMessage e))})))

;; =============================================================================
;; Tool Dispatch
;; =============================================================================

(defn- scaffold-project-handler
  "Handler for scaffold_project tool with auto-validation."
  [arguments]
  (let [output-path (or (:output_path arguments) ".")
        project-name (:project_name arguments)
        project-path (str output-path "/" project-name)
        scaffold-result (scaffold/scaffold-project
                          {:project-name project-name
                           :modules (:modules arguments)
                           :output-path output-path})]
    ;; Auto-run validation if scaffolding succeeded
    (if (:success scaffold-result)
      (let [validation-result (validate-project {:path project-path :fix true})]
        (assoc scaffold-result
               :validation validation-result
               :auto_validated true))
      scaffold-result)))

(def tool-handlers
  "Map of tool names to handler functions.
   Each handler takes an arguments map and returns the tool result.
   Handlers that take no arguments should ignore the arguments parameter."
  {"repl_eval"              eval-code
   "discover_repls"         (fn [_] (discover-repls))
   "analyze_project"        analyze-project
   "reload_namespace"       reload-namespace
   "doc_symbol"             doc-symbol
   "eval_at"                eval-at
   "run_tests"              run-tests
   "eval_comment_block"     eval-comment-block
   "validate_changed_files" validate-changed-files
   "validate_project"       validate-project
   "view_repl_logs"         view-repl-logs
   "scaffold_project"       scaffold-project-handler
   "track_process"          track-process
   "stop_project"           stop-project
   "list_tracked_processes" list-tracked-processes
   ;; Lisa Loop v2 tools
   "lisa_create_plan"           lisa-create-plan
   "lisa_get_plan"              lisa-get-plan
   "lisa_mark_checkpoint_done"  lisa-mark-checkpoint-done
   "lisa_add_checkpoint"        lisa-add-checkpoint
   "lisa_run_orchestrator"      lisa-run-orchestrator
   "repl_snapshot"              repl-snapshot
   ;; Signs (guardrails) tools
   "lisa_append_sign"   lisa-append-sign
   "lisa_get_signs"     lisa-get-signs
   "lisa_clear_signs"   lisa-clear-signs
   ;; Validation tools
   "lisa_run_validation" lisa-run-validation
   "lisa_check_gates"    lisa-check-gates
   ;; Monitoring
   "lisa_watch" lisa-watch})

(defn call-tool
  "Dispatch tool call to appropriate handler."
  [{:keys [name arguments]}]
  (if-let [handler (get tool-handlers name)]
    (handler arguments)
    {:success false :error (str "Unknown tool: " name)}))
