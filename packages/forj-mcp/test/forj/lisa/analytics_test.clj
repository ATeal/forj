(ns forj.lisa.analytics-test
  "Tests for forj.lisa.analytics"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [forj.lisa.analytics :as analytics]
            [babashka.fs :as fs]
            [cheshire.core :as json]))

;; =============================================================================
;; Test helpers
;; =============================================================================

(defn- create-test-log
  "Create a temporary test log file with the given entries."
  [entries]
  (let [tmp-file (str (fs/create-temp-file {:prefix "analytics-test-" :suffix ".jsonl"}))]
    (spit tmp-file (str/join "\n" (map json/generate-string entries)))
    tmp-file))

;; =============================================================================
;; extract-tool-calls tests
;; =============================================================================

(deftest extract-tool-calls-test
  (testing "Returns nil for non-existent file"
    (is (nil? (analytics/extract-tool-calls "/nonexistent/path/file.jsonl"))))

  (testing "Returns nil for empty file"
    (let [tmp-file (str (fs/create-temp-file {:prefix "empty-" :suffix ".jsonl"}))]
      (try
        (spit tmp-file "")
        (is (nil? (analytics/extract-tool-calls tmp-file)))
        (finally
          (fs/delete tmp-file)))))

  (testing "Extracts tool calls from assistant messages"
    (let [entries [{:type "assistant"
                    :message {:content [{:type "tool_use"
                                         :name "Read"
                                         :input {:file_path "/foo/bar.clj"}}]}}]
          tmp-file (create-test-log entries)]
      (try
        (let [result (analytics/extract-tool-calls tmp-file)]
          (is (= 1 (count result)))
          (is (= "Read" (:name (first result))))
          (is (= {:file_path "/foo/bar.clj"} (:input (first result)))))
        (finally
          (fs/delete tmp-file)))))

  (testing "Extracts multiple tool calls from single message"
    (let [entries [{:type "assistant"
                    :message {:content [{:type "tool_use"
                                         :name "Read"
                                         :input {:file_path "/foo.clj"}}
                                        {:type "tool_use"
                                         :name "Glob"
                                         :input {:pattern "*.clj"}}]}}]
          tmp-file (create-test-log entries)]
      (try
        (let [result (analytics/extract-tool-calls tmp-file)]
          (is (= 2 (count result)))
          (is (= "Read" (:name (first result))))
          (is (= "Glob" (:name (second result)))))
        (finally
          (fs/delete tmp-file)))))

  (testing "Extracts tool calls from multiple messages"
    (let [entries [{:type "assistant"
                    :message {:content [{:type "tool_use"
                                         :name "Read"
                                         :input {}}]}}
                   {:type "assistant"
                    :message {:content [{:type "tool_use"
                                         :name "Write"
                                         :input {}}]}}]
          tmp-file (create-test-log entries)]
      (try
        (let [result (analytics/extract-tool-calls tmp-file)]
          (is (= 2 (count result)))
          (is (= ["Read" "Write"] (map :name result))))
        (finally
          (fs/delete tmp-file)))))

  (testing "Ignores non-assistant messages"
    (let [entries [{:type "user" :message "hello"}
                   {:type "assistant"
                    :message {:content [{:type "tool_use"
                                         :name "Read"
                                         :input {}}]}}
                   {:type "system" :message "info"}]
          tmp-file (create-test-log entries)]
      (try
        (let [result (analytics/extract-tool-calls tmp-file)]
          (is (= 1 (count result)))
          (is (= "Read" (:name (first result)))))
        (finally
          (fs/delete tmp-file)))))

  (testing "Ignores non-tool_use content"
    (let [entries [{:type "assistant"
                    :message {:content [{:type "text"
                                         :text "Some text"}
                                        {:type "tool_use"
                                         :name "Read"
                                         :input {}}]}}]
          tmp-file (create-test-log entries)]
      (try
        (let [result (analytics/extract-tool-calls tmp-file)]
          (is (= 1 (count result)))
          (is (= "Read" (:name (first result)))))
        (finally
          (fs/delete tmp-file)))))

  (testing "Handles malformed JSON lines gracefully"
    (let [tmp-file (str (fs/create-temp-file {:prefix "malformed-" :suffix ".jsonl"}))]
      (try
        (spit tmp-file (str "not valid json\n"
                            (json/generate-string
                             {:type "assistant"
                              :message {:content [{:type "tool_use"
                                                   :name "Read"
                                                   :input {}}]}})))
        (let [result (analytics/extract-tool-calls tmp-file)]
          (is (= 1 (count result)))
          (is (= "Read" (:name (first result)))))
        (finally
          (fs/delete tmp-file))))))

;; =============================================================================
;; score-repl-compliance tests
;; =============================================================================

(deftest score-repl-compliance-test
  (testing "Returns :excellent for 2+ REPL tools with no anti-patterns"
    (let [tool-calls [{:name "mcp__forj__reload_namespace" :input {}}
                      {:name "mcp__forj__eval_comment_block" :input {}}
                      {:name "Read" :input {}}]
          result (analytics/score-repl-compliance tool-calls)]
      (is (= :excellent (:score result)))
      (is (= 2 (count (:used-repl-tools result))))
      (is (empty? (:anti-patterns result)))))

  (testing "Returns :good for 1 REPL tool with no anti-patterns"
    (let [tool-calls [{:name "mcp__forj__reload_namespace" :input {}}
                      {:name "Read" :input {}}
                      {:name "Write" :input {}}]
          result (analytics/score-repl-compliance tool-calls)]
      (is (= :good (:score result)))
      (is (= 1 (count (:used-repl-tools result))))
      (is (empty? (:anti-patterns result)))))

  (testing "Returns :good for 1 REPL tool with 1 anti-pattern"
    (let [tool-calls [{:name "mcp__forj__reload_namespace" :input {}}
                      {:name "Bash" :input {:command "bb -e '(+ 1 2)'"}}]
          result (analytics/score-repl-compliance tool-calls)]
      (is (= :good (:score result)))
      (is (= 1 (count (:used-repl-tools result))))
      (is (= 1 (count (:anti-patterns result))))))

  (testing "Returns :fair for no REPL tools but limited anti-patterns"
    (let [tool-calls [{:name "Read" :input {}}
                      {:name "Write" :input {}}
                      {:name "Bash" :input {:command "bb -e '(+ 1 2)'"}}]
          result (analytics/score-repl-compliance tool-calls)]
      (is (= :fair (:score result)))
      (is (empty? (:used-repl-tools result)))
      (is (= 1 (count (:anti-patterns result))))))

  (testing "Returns :fair for empty tool calls"
    (let [result (analytics/score-repl-compliance [])]
      (is (= :fair (:score result)))
      (is (empty? (:used-repl-tools result)))
      (is (empty? (:anti-patterns result)))))

  (testing "Returns :fair for nil tool calls"
    (let [result (analytics/score-repl-compliance nil)]
      (is (= :fair (:score result)))
      (is (empty? (:used-repl-tools result)))
      (is (empty? (:anti-patterns result)))))

  (testing "Returns :poor for many anti-patterns and no REPL tools"
    (let [tool-calls [{:name "Bash" :input {:command "bb -e '(+ 1 2)'"}}
                      {:name "Bash" :input {:command "clj -e '(println 1)'"}}
                      {:name "Bash" :input {:command "lein run"}}]
          result (analytics/score-repl-compliance tool-calls)]
      (is (= :poor (:score result)))
      (is (empty? (:used-repl-tools result)))
      (is (= 3 (count (:anti-patterns result))))))

  (testing "Recognizes all REPL tools"
    (let [all-repl-tools [{:name "mcp__forj__repl_eval" :input {}}
                          {:name "mcp__forj__reload_namespace" :input {}}
                          {:name "mcp__forj__eval_at" :input {}}
                          {:name "mcp__forj__eval_comment_block" :input {}}
                          {:name "mcp__forj__doc_symbol" :input {}}
                          {:name "mcp__forj__validate_changed_files" :input {}}
                          {:name "mcp__forj__run_tests" :input {}}
                          {:name "mcp__forj__repl_snapshot" :input {}}]
          result (analytics/score-repl-compliance all-repl-tools)]
      (is (= :excellent (:score result)))
      (is (= 8 (count (:used-repl-tools result))))))

  (testing "Detects all anti-pattern commands"
    (let [anti-pattern-calls [{:name "Bash" :input {:command "bb -e '(+ 1 2)'"}}
                              {:name "Bash" :input {:command "bb -cp src foo.clj"}}
                              {:name "Bash" :input {:command "clj -e '(+ 1 2)'"}}
                              {:name "Bash" :input {:command "clj -M:dev -e '(+ 1 2)'"}}
                              {:name "Bash" :input {:command "lein run"}}
                              {:name "Bash" :input {:command "lein repl"}}
                              {:name "Bash" :input {:command "echo '(+ 1 2)' | bb"}}
                              {:name "Bash" :input {:command "echo '(+ 1 2)' | clj"}}]
          result (analytics/score-repl-compliance anti-pattern-calls)]
      (is (= :poor (:score result)))
      (is (= 8 (count (:anti-patterns result))))))

  (testing "Does not flag normal Bash commands as anti-patterns"
    (let [tool-calls [{:name "mcp__forj__reload_namespace" :input {}}
                      {:name "mcp__forj__run_tests" :input {}}
                      {:name "Bash" :input {:command "git status"}}
                      {:name "Bash" :input {:command "npm install"}}
                      {:name "Bash" :input {:command "ls -la"}}]
          result (analytics/score-repl-compliance tool-calls)]
      (is (= :excellent (:score result)))
      (is (= 2 (count (:used-repl-tools result))))
      (is (empty? (:anti-patterns result)))))

  (testing "Deduplicates REPL tools in result"
    (let [tool-calls [{:name "mcp__forj__reload_namespace" :input {}}
                      {:name "mcp__forj__reload_namespace" :input {}}
                      {:name "mcp__forj__reload_namespace" :input {}}]
          result (analytics/score-repl-compliance tool-calls)]
      (is (= 1 (count (:used-repl-tools result))))
      (is (= ["mcp__forj__reload_namespace"] (:used-repl-tools result))))))

(comment
  ;; Run tests from REPL
  (clojure.test/run-tests 'forj.lisa.analytics-test)
  )
