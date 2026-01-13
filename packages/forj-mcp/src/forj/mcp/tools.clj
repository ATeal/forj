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
                                      :description "Project path (defaults to current directory)"}}}}])

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
    {:success false :error (str "Unknown tool: " name)}))
