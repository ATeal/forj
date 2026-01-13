(ns forj.mcp.tools
  "MCP tool implementations for REPL connectivity."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [edamame.core :as edamame]))

(def tools
  "Tool definitions for MCP tools/list response."
  [{:name "repl_eval"
    :description "Evaluate Clojure code in an nREPL server. Returns the evaluation result or error."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"
                                      :description "Clojure code to evaluate"}
                               :port {:type "integer"
                                      :description "nREPL port (auto-discovered if not provided)"}
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
    :description "Run tests for a Clojure project. Auto-detects test runner (bb test, clj -M:test, lein test)."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"
                                      :description "Project path (defaults to current directory)"}
                               :namespace {:type "string"
                                           :description "Specific namespace to test (optional)"}
                               :runner {:type "string"
                                        :enum ["bb" "clj" "lein" "auto"]
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
                                      :description "nREPL port (auto-discovered if not provided)"}}}}])

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
  "Evaluate Clojure code via clj-nrepl-eval."
  [{:keys [code port timeout]}]
  (if-not port
    ;; Auto-discover port if not provided
    (let [{:keys [success ports error]} (discover-repls)]
      (if success
        (if-let [first-port (first (extract-ports ports))]
          (eval-code {:code code :port first-port :timeout timeout})
          {:success false :error "No nREPL ports found"})
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
           :error (str "No suitable REPL found for " (name detected-type)
                       " file. Start one with `bb nrepl` or `clj -M:dev`")})
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

(defn- detect-test-runner
  "Detect the appropriate test runner for a project."
  [path]
  (let [file-exists? (fn [f] (.exists (java.io.File. path f)))]
    (cond
      (file-exists? "bb.edn") :bb
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
           :error (str "No suitable REPL found for " (name detected-type)
                       " file. Start one with `bb nrepl` or `clj -M:dev`")})
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
           :error "No nREPL server found. Start one with `bb nrepl` or `clj -M:dev`"})))
    (catch Exception e
      {:success false
       :error (str "Failed to validate files: " (.getMessage e))})))

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
    {:success false :error (str "Unknown tool: " name)}))
