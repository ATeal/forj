(ns forj.mcp.tools
  "MCP tool implementations for REPL connectivity."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

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
                  :required ["file" "line"]}}])

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
  "Parse file and return vector of {:start-line :end-line :form} for each top-level form."
  [content]
  (let [lines (str/split-lines content)
        indexed-lines (map-indexed #(vector (inc %1) %2) lines)]
    (loop [remaining indexed-lines
           depth 0
           current-start nil
           current-lines []
           forms []]
      (if (empty? remaining)
        ;; Return accumulated forms
        (if (seq current-lines)
          (conj forms {:start-line current-start
                       :end-line (first (last current-lines))
                       :form (str/join "\n" (map second current-lines))})
          forms)
        (let [[line-num line-text] (first remaining)
              ;; Count parens (simplified - doesn't handle strings/comments perfectly)
              opens (count (re-seq #"\(|\[|\{" line-text))
              closes (count (re-seq #"\)|\]|\}" line-text))
              new-depth (+ depth opens (- closes))
              trimmed (str/trim line-text)]
          (cond
            ;; Skip empty lines and comments at top level
            (and (zero? depth) (or (str/blank? trimmed) (str/starts-with? trimmed ";")))
            (recur (rest remaining) 0 nil [] forms)

            ;; Starting a new form
            (and (zero? depth) (pos? opens))
            (recur (rest remaining)
                   new-depth
                   line-num
                   [[line-num line-text]]
                   forms)

            ;; Continuing a form
            (pos? depth)
            (let [updated-lines (conj current-lines [line-num line-text])]
              (if (zero? new-depth)
                ;; Form complete
                (recur (rest remaining)
                       0
                       nil
                       []
                       (conj forms {:start-line current-start
                                    :end-line line-num
                                    :form (str/join "\n" (map second updated-lines))}))
                ;; Form continues
                (recur (rest remaining)
                       new-depth
                       current-start
                       updated-lines
                       forms)))

            :else
            (recur (rest remaining) depth current-start current-lines forms)))))))

(defn- extract-ns-from-content
  "Extract namespace name from file content."
  [content]
  (when-let [match (re-find #"\(ns\s+([^\s\)\(]+)" content)]
    (second match)))

(defn- find-form-at-line
  "Find the form containing the given line number."
  [forms line scope]
  (if (= scope "inner")
    ;; For inner, we'd need more sophisticated parsing - for now, fall back to root
    ;; TODO: Implement proper inner form detection
    (first (filter #(and (<= (:start-line %) line)
                         (>= (:end-line %) line))
                   forms))
    ;; Root scope - find top-level form containing line
    (first (filter #(and (<= (:start-line %) line)
                         (>= (:end-line %) line))
                   forms))))

(defn eval-at
  "Evaluate a form at a specific line in a file."
  [{:keys [file line scope port] :or {scope "root"}}]
  (try
    (let [content (slurp file)
          ns-name (extract-ns-from-content content)
          forms (find-top-level-forms content)
          target-form (find-form-at-line forms line scope)]
      (if target-form
        (let [;; Prepend ns switch if we found a namespace
              code (if ns-name
                     (str "(ns " ns-name ") " (:form target-form))
                     (:form target-form))
              result (eval-code {:code code :port port})]
          (if (:success result)
            {:success true
             :file file
             :lines [(:start-line target-form) (:end-line target-form)]
             :namespace ns-name
             :value (:value result)}
            result))
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
    {:success false :error (str "Unknown tool: " name)}))
