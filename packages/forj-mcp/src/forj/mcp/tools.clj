(ns forj.mcp.tools
  "MCP tool implementations for REPL connectivity."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [forj.hooks.loop-state :as loop-state]
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

   ;; Lisa Loop management tools
   {:name "start_loop"
    :description "Start a Lisa Loop autonomous development session. Initializes loop state for REPL-driven iterative development."
    :inputSchema {:type "object"
                  :properties {:prompt {:type "string"
                                        :description "The task/goal for this loop"}
                               :max_iterations {:type "integer"
                                                :description "Maximum iterations before stopping (default: 30)"}
                               :completion_promise {:type "string"
                                                    :description "Text to output when complete (default: 'COMPLETE')"}}
                  :required ["prompt"]}}

   {:name "cancel_loop"
    :description "Cancel the active Lisa Loop. Clears loop state and allows normal session exit."
    :inputSchema {:type "object"
                  :properties {}}}

   {:name "loop_status"
    :description "Check the status of the current Lisa Loop. Returns active state, iteration count, and history."
    :inputSchema {:type "object"
                  :properties {}}}

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
                  :required ["project_name" "modules"]}}])

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
    (let [result (p/shell {:out :string :err :string :continue true}
                          "clj-nrepl-eval" "--discover-ports")]
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
            result (apply p/shell {:out :string :err :string :continue true} args)]
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
       :error (str "Failed to eval at line: " (.getMessage e))})))

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
  (try
    (let [effective-runner (if (= runner "auto")
                             (detect-test-runner path)
                             (keyword runner))
          cmd (build-test-command effective-runner namespace)]
      (if cmd
        (let [result (apply p/shell {:out :string :err :string :continue true :dir path} cmd)
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
       :error (str "Failed to run tests: " (.getMessage e))})))

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
       :error (str "Failed to eval comment block: " (.getMessage e))})))

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
;; Lisa Loop Management
;; =============================================================================

(defn start-loop
  "Start a Lisa Loop autonomous development session."
  [{:keys [prompt max_iterations completion_promise]}]
  (try
    (let [config {:prompt prompt
                  :max-iterations (or max_iterations 30)
                  :completion-promise (or completion_promise "COMPLETE")}
          state (loop-state/start-loop! config)]
      {:success true
       :message "Lisa Loop started"
       :state state})
    (catch Exception e
      {:success false
       :error (str "Failed to start loop: " (.getMessage e))})))

(defn cancel-loop
  "Cancel the active Lisa Loop."
  [_]
  (try
    (if (loop-state/active?)
      (do
        (loop-state/clear-state!)
        {:success true
         :message "Lisa Loop cancelled"})
      {:success true
       :message "No active loop to cancel"})
    (catch Exception e
      {:success false
       :error (str "Failed to cancel loop: " (.getMessage e))})))

(defn loop-status
  "Check the status of the current Lisa Loop."
  [_]
  (try
    (if-let [state (loop-state/read-state)]
      {:success true
       :active (:active? state)
       :iteration (:iteration state)
       :max-iterations (:max-iterations state)
       :prompt (:prompt state)
       :completion-promise (:completion-promise state)
       :started-at (:started-at state)
       :validation-history-count (count (:validation-history state))}
      {:success true
       :active false
       :message "No active loop"})
    (catch Exception e
      {:success false
       :error (str "Failed to get loop status: " (.getMessage e))})))

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

(defn- check-bb-tasks-use-clj
  "Check if bb.edn tasks use 'clj' which requires rlwrap (fails headless)."
  [path]
  (let [bb-file (java.io.File. path "bb.edn")]
    (when (.exists bb-file)
      (try
        (let [content (slurp bb-file)]
          ;; Look for (shell "clj ...) pattern
          (when (re-find #"\(shell\s+\"clj\s" content)
            {:issue :bb-tasks-use-clj
             :message "bb.edn tasks use 'clj' which requires rlwrap - use 'clojure' instead"
             :fix-available true}))
        (catch Exception _ nil)))))

(defn- fix-bb-tasks-clj-to-clojure
  "Replace 'clj' with 'clojure' in bb.edn shell commands."
  [path]
  (let [bb-file (java.io.File. path "bb.edn")]
    (try
      (let [content (slurp bb-file)
            fixed (str/replace content #"\"clj " "\"clojure ")]
        (if (= content fixed)
          {:fix-failed :bb-tasks-use-clj
           :message "No 'clj' commands found to replace"}
          (do
            (spit bb-file fixed)
            {:fixed :bb-tasks-use-clj
             :message "Replaced 'clj' with 'clojure' in bb.edn tasks"})))
      (catch Exception e
        {:fix-failed :bb-tasks-use-clj
         :message (str "Failed to fix: " (.getMessage e))}))))

(defn- check-deps-resolve
  "Check if deps.edn dependencies resolve."
  [path]
  (let [deps-file (java.io.File. path "deps.edn")]
    (when (.exists deps-file)
      (try
        ;; Use 'clojure' not 'clj' since clj requires rlwrap
        (let [result (p/shell {:out :string :err :string :continue true :dir path}
                              "clojure" "-Spath")]
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

(defn- mise-available?
  "Check if mise is installed."
  []
  (try
    (let [result (p/shell {:out :string :err :string :continue true} "which" "mise")]
      (zero? (:exit result)))
    (catch Exception _ false)))

(defn- get-java-version
  "Get the current Java major version."
  [path]
  (try
    (let [result (p/shell {:out :string :err :string :continue true :dir path}
                          "java" "-version")
          version-str (or (:err result) (:out result))
          version-match (re-find #"version \"(\d+)" version-str)]
      (when version-match (parse-long (second version-match))))
    (catch Exception _ nil)))

(defn- check-java-version
  "Check Java version meets minimum for shadow-cljs."
  [path min-version]
  (let [shadow-file (java.io.File. path "shadow-cljs.edn")]
    (when (.exists shadow-file)
      (try
        (let [major-version (get-java-version path)]
          (when (and major-version (< major-version min-version))
            {:issue :java-version-low
             :message (str "Java " major-version " found, but shadow-cljs requires Java " min-version "+")
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
    (let [result (p/shell {:out :string :err :string :continue true :dir path}
                          "npm" "install")]
      (if (zero? (:exit result))
        {:fixed :npm-install
         :message "Ran npm install successfully"}
        {:fix-failed :npm-install
         :message (str "npm install failed: " (:err result))}))
    (catch Exception e
      {:fix-failed :npm-install
       :message (str "Failed to run npm install: " (.getMessage e))})))

(defn- fix-java-version-mise
  "Create .mise.toml with Java 21 for shadow-cljs projects."
  [path]
  (let [mise-file (java.io.File. path ".mise.toml")]
    (try
      (if (.exists mise-file)
        ;; Append java to existing file
        (let [content (slurp mise-file)]
          (if (str/includes? content "java")
            {:fix-failed :java-version
             :message ".mise.toml already has java configured"}
            (do
              (spit mise-file (str content "\n[tools]\njava = \"21\"\n"))
              {:fixed :java-version
               :message "Added java = \"21\" to .mise.toml - run 'mise install' to install"})))
        ;; Create new file
        (do
          (spit mise-file "[tools]\njava = \"21\"\n")
          {:fixed :java-version
           :message "Created .mise.toml with java = \"21\" - run 'mise install' to install"}))
      (catch Exception e
        {:fix-failed :java-version
         :message (str "Failed to create .mise.toml: " (.getMessage e))}))))

(defn validate-project
  "Validate a Clojure project setup. Checks common issues after scaffolding."
  [{:keys [path fix] :or {path "." fix false}}]
  (try
    (let [;; Run all checks
          checks [(check-bb-override-builtin path)
                  (check-bb-tasks-use-clj path)
                  (check-deps-resolve path)
                  (check-npm-install path)
                  (check-java-version path 21)
                  (check-shadow-cljs-upgrade path)]
          issues (remove nil? checks)

          ;; Apply fixes if requested
          fixes (when fix
                  (remove nil?
                          [(when (some #(= (:issue %) :bb-repl-override) issues)
                             (fix-bb-override-builtin path))
                           (when (some #(= (:issue %) :bb-tasks-use-clj) issues)
                             (fix-bb-tasks-clj-to-clojure path))
                           (when (some #(= (:issue %) :npm-not-installed) issues)
                             (run-npm-install path))
                           ;; NOTE: Java version fix via mise disabled - npm install usually
                           ;; resolves shadow-cljs issues. Enable if mise setup is desired:
                           #_(when (some #(and (= (:issue %) :java-version-low)
                                               (:fix-available %)) issues)
                               (fix-java-version-mise path))]))

          ;; Re-check issues after fixes to update status
          ;; Java version is informational only (doesn't block success)
          remaining-issues (if fix
                             (remove nil?
                                     [(check-bb-override-builtin path)
                                      (check-bb-tasks-use-clj path)
                                      (check-deps-resolve path)
                                      (check-npm-install path)])
                             ;; Filter out informational issues for success check
                             (remove #(= (:issue %) :java-version-low) issues))
          ;; Keep java version in issues for info, but don't let it block success
          info-issues (filter #(= (:issue %) :java-version-low) issues)
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

(defn call-tool
  "Dispatch tool call to appropriate handler."
  [{:keys [name arguments]}]
  (case name
    "repl_eval" (eval-code arguments)
    "discover_repls" (discover-repls)
    "analyze_project" (analyze-project arguments)
    "reload_namespace" (reload-namespace arguments)
    "doc_symbol" (doc-symbol arguments)
    "eval_at" (eval-at arguments)
    "run_tests" (run-tests arguments)
    "eval_comment_block" (eval-comment-block arguments)
    "validate_changed_files" (validate-changed-files arguments)
    "start_loop" (start-loop arguments)
    "cancel_loop" (cancel-loop arguments)
    "loop_status" (loop-status arguments)
    "validate_project" (validate-project arguments)
    "view_repl_logs" (view-repl-logs arguments)
    "scaffold_project" (scaffold/scaffold-project
                        {:project-name (:project_name arguments)
                         :modules (:modules arguments)
                         :output-path (or (:output_path arguments) ".")})
    {:success false :error (str "Unknown tool: " name)}))
