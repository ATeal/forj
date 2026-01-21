(ns forj.hooks.session-start-test
  "Tests for forj.hooks.session-start"
  (:require [clojure.test :refer [deftest testing is]]
            [forj.hooks.session-start :as session-start]
            [forj.hooks.util :as util]
            [cheshire.core :as json]))

;; =============================================================================
;; is-clojure-project? tests
;; =============================================================================

(deftest is-clojure-project?-test
  (testing "Detects Clojure project by marker files"
    ;; Current directory (forj) has bb.edn
    (is (util/is-clojure-project? "."))
    (is (util/is-clojure-project? (System/getProperty "user.dir"))))

  (testing "Non-Clojure directories"
    (is (not (util/is-clojure-project? "/tmp")))))

;; =============================================================================
;; analyze-project tests
;; =============================================================================

(deftest analyze-project-test
  (testing "Analyzes forj project"
    (let [result (session-start/analyze-project ".")]
      (is (contains? (:project-types result) :babashka))
      (is (seq (:bb-tasks result))))))

;; =============================================================================
;; format-context tests
;; =============================================================================

(deftest format-context-test
  (testing "Formats context string"
    (let [analysis {:project-types #{:babashka}
                    :bb-tasks [:dev :test]
                    :deps-aliases nil
                    :shadow-builds nil}
          context (session-start/format-context analysis "localhost:1668 (bb)" true)]
      (is (string? context))
      (is (re-find #"CLOJURE PROJECT DETECTED" context))
      (is (re-find #"babashka" context))
      (is (re-find #"localhost:1668" context))
      (is (re-find #"repl_eval" context))
      (is (re-find #"LSP tools available" context))))

  (testing "Shows LSP warning when not available"
    (let [analysis {:project-types #{:clojure} :bb-tasks nil :deps-aliases nil :shadow-builds nil}
          context (session-start/format-context analysis nil false)]
      (is (re-find #"WARNING.*clojure-lsp" context)))))

;; =============================================================================
;; Hook output format tests
;; =============================================================================

(deftest hook-output-format-test
  (testing "Hook produces valid JSON"
    ;; Simulate what -main would output
    (let [analysis (session-start/analyze-project ".")
          repls (util/discover-repls)
          context (session-start/format-context analysis repls true)
          output {:hookSpecificOutput
                  {:hookEventName "SessionStart"
                   :additionalContext context}}
          json-str (json/generate-string output)]
      ;; Should parse back correctly
      (is (map? (json/parse-string json-str)))
      ;; Should have required structure
      (let [parsed (json/parse-string json-str true)]
        (is (= "SessionStart" (get-in parsed [:hookSpecificOutput :hookEventName])))
        (is (string? (get-in parsed [:hookSpecificOutput :additionalContext])))))))
