(ns forj.mcp.tools-test
  "Tests for forj.mcp.tools"
  (:require [clojure.test :refer [deftest testing is are]]
            [forj.mcp.tools :as tools]))

;; =============================================================================
;; detect-repl-type tests
;; =============================================================================

(deftest detect-repl-type-test
  (testing "File extension detection"
    (are [path expected] (= expected (tools/detect-repl-type path))
      "src/app.cljs"         :clojurescript
      "src/app.bb"           :babashka
      "scripts/build.bb"     :babashka
      "frontend/app.cljs"    :clojurescript))

  (testing "Path pattern detection"
    (are [path expected] (= expected (tools/detect-repl-type path))
      "src/cljs/app.clj"           :clojurescript
      "src/clojurescript/core.clj" :clojurescript
      "src-cljs/views.clj"         :clojurescript
      "bb/tasks.clj"               :babashka
      "scripts/deploy.clj"         :babashka
      "tasks/build.clj"            :babashka))

  (testing "Unknown extension"
    (is (= :unknown (tools/detect-repl-type "README.md")))
    (is (= :unknown (tools/detect-repl-type "package.json")))))

;; =============================================================================
;; select-repl-for-file tests
;; =============================================================================

(deftest select-repl-for-file-test
  (let [repls "localhost:1668 (bb)\nlocalhost:9000 (cljs)\nlocalhost:7888 (clj)"]
    (testing "Selects correct REPL by file type"
      (is (= 9000 (tools/select-repl-for-file repls "src/app.cljs")))
      (is (= 1668 (tools/select-repl-for-file repls "scripts/build.bb"))))

    (testing "Falls back to first available when no match"
      (is (some? (tools/select-repl-for-file repls "README.md"))))))

;; =============================================================================
;; file-path->namespace tests
;; =============================================================================

(deftest file-path->namespace-test
  (testing "Converts file paths to namespace names"
    (are [path expected] (= expected (#'tools/file-path->namespace path))
      "src/forj/mcp/tools.clj"                    "forj.mcp.tools"
      "src/my_app/core.clj"                       "my-app.core"
      "packages/forj-mcp/src/forj/mcp/server.clj" "forj.mcp.server"
      "test/forj/mcp/tools_test.clj"              "forj.mcp.tools-test")))

;; =============================================================================
;; extract-ns-from-content tests
;; =============================================================================

(deftest extract-ns-from-content-test
  (testing "Extracts namespace from file content"
    (is (= "forj.mcp.tools"
           (#'tools/extract-ns-from-content "(ns forj.mcp.tools\n  (:require [clojure.string]))")))
    (is (= "my-app.core"
           (#'tools/extract-ns-from-content "(ns my-app.core)")))
    (is (nil? (#'tools/extract-ns-from-content "; just a comment")))))

;; =============================================================================
;; find-top-level-forms tests
;; =============================================================================

(deftest find-top-level-forms-test
  (testing "Parses top-level forms with line info"
    (let [content "(defn foo [] 1)\n\n(defn bar [] 2)"
          forms (#'tools/find-top-level-forms content)]
      (is (= 2 (count forms)))
      (is (= 1 (:start-line (first forms))))
      (is (= 3 (:start-line (second forms))))))

  (testing "Handles multi-line forms"
    (let [content "(defn foo\n  [x]\n  (+ x 1))"
          forms (#'tools/find-top-level-forms content)]
      (is (= 1 (count forms)))
      (is (= 1 (:start-line (first forms))))
      (is (= 3 (:end-line (first forms)))))))

;; =============================================================================
;; find-form-at-line tests
;; =============================================================================

(deftest find-form-at-line-test
  (testing "Finds form containing given line"
    (let [forms [{:start-line 1 :end-line 3 :form "(defn foo [] 1)"}
                 {:start-line 5 :end-line 7 :form "(defn bar [] 2)"}]]
      (is (= "(defn foo [] 1)" (:form (#'tools/find-form-at-line forms 2))))
      (is (= "(defn bar [] 2)" (:form (#'tools/find-form-at-line forms 6))))
      (is (nil? (#'tools/find-form-at-line forms 4))))))

;; =============================================================================
;; analyze-project tests
;; =============================================================================

(deftest analyze-project-test
  (testing "Analyzes current project (forj)"
    (let [result (tools/analyze-project {})]
      (is (:success result))
      (is (contains? (:project-types result) :babashka))
      (is (seq (:bb-tasks result))))))

;; =============================================================================
;; Tool definitions tests
;; =============================================================================

(deftest tools-definition-test
  (testing "All tools have required fields"
    (doseq [tool tools/tools]
      (is (string? (:name tool)) (str "Tool missing name: " tool))
      (is (string? (:description tool)) (str "Tool missing description: " (:name tool)))
      (is (map? (:inputSchema tool)) (str "Tool missing inputSchema: " (:name tool)))))

  (testing "Expected tools are defined"
    (let [tool-names (set (map :name tools/tools))]
      (is (contains? tool-names "repl_eval"))
      (is (contains? tool-names "discover_repls"))
      (is (contains? tool-names "eval_at"))
      (is (contains? tool-names "run_tests"))
      (is (contains? tool-names "doc_symbol"))
      (is (contains? tool-names "reload_namespace"))
      (is (contains? tool-names "analyze_project")))))

;; =============================================================================
;; call-tool dispatch tests
;; =============================================================================

(deftest call-tool-test
  (testing "Returns error for unknown tool"
    (let [result (tools/call-tool {:name "unknown_tool" :arguments {}})]
      (is (false? (:success result)))
      (is (re-find #"Unknown tool" (:error result)))))

  (testing "Dispatches to analyze_project"
    (let [result (tools/call-tool {:name "analyze_project" :arguments {}})]
      (is (:success result)))))
