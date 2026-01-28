(ns forj.lisa.claude-sessions-test
  "Tests for forj.lisa.claude-sessions - Claude session log parsing"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [forj.lisa.claude-sessions :as sessions]
            [babashka.fs :as fs]
            [cheshire.core :as json]))

;; =============================================================================
;; Test helpers
;; =============================================================================

(defn- create-test-jsonl
  "Create a temporary JSONL file with the given entries."
  [entries]
  (let [tmp-file (str (fs/create-temp-file {:prefix "session-test-" :suffix ".jsonl"}))]
    (spit tmp-file (str/join "\n" (map json/generate-string entries)))
    tmp-file))

;; =============================================================================
;; encode-project-path tests
;; =============================================================================

(deftest encode-project-path-test
  (testing "Encodes absolute path with leading slash"
    (is (= "-home-user-projects-foo"
           (sessions/encode-project-path "/home/user/projects/foo"))))

  (testing "Handles paths with multiple levels"
    (is (= "-home-user-Projects-github-forj"
           (sessions/encode-project-path "/home/user/Projects/github/forj"))))

  (testing "Handles single directory"
    (is (= "-tmp"
           (sessions/encode-project-path "/tmp"))))

  (testing "Handles root path"
    (is (= "-"
           (sessions/encode-project-path "/")))))

;; =============================================================================
;; session-log-path tests
;; =============================================================================

(deftest session-log-path-test
  (testing "Generates correct path with explicit project path"
    (let [path (sessions/session-log-path "abc-123" "/home/user/myproject")]
      (is (str/ends-with? (str path) "abc-123.jsonl"))
      (is (str/includes? (str path) "-home-user-myproject"))
      (is (str/includes? (str path) ".claude/projects"))))

  (testing "Path includes .jsonl extension"
    (let [path (sessions/session-log-path "test-session-id" "/foo/bar")]
      (is (str/ends-with? (str path) ".jsonl")))))

;; =============================================================================
;; read-session-jsonl tests
;; =============================================================================

(deftest read-session-jsonl-test
  (testing "Returns nil for non-existent file"
    (is (nil? (sessions/read-session-jsonl "/nonexistent/path/file.jsonl"))))

  (testing "Returns empty seq for empty file"
    (let [tmp-file (str (fs/create-temp-file {:prefix "empty-" :suffix ".jsonl"}))]
      (try
        (spit tmp-file "")
        (is (empty? (sessions/read-session-jsonl tmp-file)))
        (finally
          (fs/delete tmp-file)))))

  (testing "Parses single JSON entry"
    (let [entries [{:type "assistant" :message {:content []}}]
          tmp-file (create-test-jsonl entries)]
      (try
        (let [result (sessions/read-session-jsonl tmp-file)]
          (is (= 1 (count result)))
          (is (= "assistant" (:type (first result)))))
        (finally
          (fs/delete tmp-file)))))

  (testing "Parses multiple JSON entries"
    (let [entries [{:type "user" :message "hello"}
                   {:type "assistant" :message {:content []}}
                   {:type "system" :info "test"}]
          tmp-file (create-test-jsonl entries)]
      (try
        (let [result (sessions/read-session-jsonl tmp-file)]
          (is (= 3 (count result)))
          (is (= ["user" "assistant" "system"] (map :type result))))
        (finally
          (fs/delete tmp-file)))))

  (testing "Handles malformed JSON lines gracefully"
    (let [tmp-file (str (fs/create-temp-file {:prefix "malformed-" :suffix ".jsonl"}))]
      (try
        (spit tmp-file (str "not valid json\n"
                            (json/generate-string {:type "assistant" :ok true})))
        (let [result (sessions/read-session-jsonl tmp-file)]
          (is (= 1 (count result)))
          (is (= "assistant" (:type (first result)))))
        (finally
          (fs/delete tmp-file))))))

;; =============================================================================
;; extract-tool-calls tests
;; =============================================================================

(deftest extract-tool-calls-test
  (testing "Extracts tool calls from assistant messages"
    (let [entries [{:type "assistant"
                    :message {:content [{:type "tool_use"
                                         :name "Read"
                                         :id "tool_123"
                                         :input {:file_path "/foo/bar.clj"}}]}}]
          result (sessions/extract-tool-calls entries)]
      (is (= 1 (count result)))
      (is (= "Read" (:name (first result))))
      (is (= "tool_123" (:id (first result))))
      (is (= {:file_path "/foo/bar.clj"} (:input (first result))))))

  (testing "Extracts multiple tool calls from single message"
    (let [entries [{:type "assistant"
                    :message {:content [{:type "tool_use"
                                         :name "Read"
                                         :id "t1"
                                         :input {:file_path "/foo.clj"}}
                                        {:type "tool_use"
                                         :name "Glob"
                                         :id "t2"
                                         :input {:pattern "*.clj"}}]}}]
          result (sessions/extract-tool-calls entries)]
      (is (= 2 (count result)))
      (is (= ["Read" "Glob"] (map :name result)))))

  (testing "Extracts tool calls from multiple messages"
    (let [entries [{:type "assistant"
                    :message {:content [{:type "tool_use"
                                         :name "Read"
                                         :id "t1"
                                         :input {}}]}}
                   {:type "assistant"
                    :message {:content [{:type "tool_use"
                                         :name "Write"
                                         :id "t2"
                                         :input {}}]}}]
          result (sessions/extract-tool-calls entries)]
      (is (= 2 (count result)))
      (is (= ["Read" "Write"] (map :name result)))))

  (testing "Ignores non-assistant messages"
    (let [entries [{:type "user" :message "hello"}
                   {:type "assistant"
                    :message {:content [{:type "tool_use"
                                         :name "Read"
                                         :id "t1"
                                         :input {}}]}}
                   {:type "system" :message "info"}]
          result (sessions/extract-tool-calls entries)]
      (is (= 1 (count result)))
      (is (= "Read" (:name (first result))))))

  (testing "Ignores non-tool_use content"
    (let [entries [{:type "assistant"
                    :message {:content [{:type "text"
                                         :text "Some text"}
                                        {:type "tool_use"
                                         :name "Read"
                                         :id "t1"
                                         :input {}}]}}]
          result (sessions/extract-tool-calls entries)]
      (is (= 1 (count result)))
      (is (= "Read" (:name (first result))))))

  (testing "Returns empty seq for entries without tool calls"
    (let [entries [{:type "assistant"
                    :message {:content [{:type "text" :text "Hello"}]}}
                   {:type "user" :message "Hi"}]
          result (sessions/extract-tool-calls entries)]
      (is (empty? result))))

  (testing "Handles nil/empty entries"
    (is (empty? (sessions/extract-tool-calls nil)))
    (is (empty? (sessions/extract-tool-calls [])))))

;; =============================================================================
;; tool-call-counts tests
;; =============================================================================

(deftest tool-call-counts-test
  (testing "Counts tool calls by name"
    (let [tool-calls [{:name "Read" :id "t1"}
                      {:name "Read" :id "t2"}
                      {:name "Write" :id "t3"}]
          result (sessions/tool-call-counts tool-calls)]
      (is (= {"Read" 2 "Write" 1} result))))

  (testing "Sorts by count descending"
    (let [tool-calls [{:name "Read" :id "t1"}
                      {:name "Edit" :id "t2"}
                      {:name "Edit" :id "t3"}
                      {:name "Edit" :id "t4"}
                      {:name "Write" :id "t5"}
                      {:name "Write" :id "t6"}]
          result (sessions/tool-call-counts tool-calls)
          result-seq (seq result)]
      (is (= ["Edit" 3] (first result-seq)))
      (is (= ["Write" 2] (second result-seq)))
      (is (= ["Read" 1] (nth result-seq 2)))))

  (testing "Returns empty map for empty input"
    (is (empty? (sessions/tool-call-counts [])))
    (is (empty? (sessions/tool-call-counts nil)))))

;; =============================================================================
;; session-tool-summary tests
;; =============================================================================

(deftest session-tool-summary-test
  (testing "Returns exists? false for non-existent session"
    (let [result (sessions/session-tool-summary "nonexistent-session-id" "/tmp")]
      (is (false? (:exists? result)))
      (is (= "nonexistent-session-id" (:session-id result)))
      (is (string? (:path result)))))

  (testing "Returns full summary for existing session"
    ;; Create a temporary directory structure to simulate Claude's layout
    (let [tmp-dir (str (fs/create-temp-dir {:prefix "claude-test-"}))
          project-path "/test/project"
          session-id "test-session-123"
          encoded-path (sessions/encode-project-path project-path)
          session-dir (fs/path tmp-dir encoded-path)]
      (try
        ;; Create the directory structure
        (fs/create-dirs session-dir)
        (let [session-file (fs/path session-dir (str session-id ".jsonl"))
              entries [{:type "assistant"
                        :message {:content [{:type "tool_use"
                                             :name "Read"
                                             :id "t1"
                                             :input {:file_path "/foo.clj"}}]}}
                       {:type "assistant"
                        :message {:content [{:type "tool_use"
                                             :name "Read"
                                             :id "t2"
                                             :input {:file_path "/bar.clj"}}
                                            {:type "tool_use"
                                             :name "Edit"
                                             :id "t3"
                                             :input {}}]}}
                       {:type "user" :message "test"}]]
          ;; Write the test session file
          (spit (str session-file)
                (str/join "\n" (map json/generate-string entries)))
          ;; Mock the projects-dir to use our temp directory
          (with-redefs [sessions/claude-projects-dir (constantly tmp-dir)]
            (let [result (sessions/session-tool-summary session-id project-path)]
              (is (true? (:exists? result)))
              (is (= session-id (:session-id result)))
              (is (= 3 (:entry-count result)))
              (is (= 3 (:total-calls result)))
              (is (= {"Read" 2 "Edit" 1} (:tool-counts result))))))
        (finally
          (fs/delete-tree tmp-dir))))))

;; =============================================================================
;; End-to-end integration tests
;; =============================================================================

(deftest end-to-end-flow-test
  (testing "Full flow: create session file, read, extract, count"
    (let [tmp-dir (str (fs/create-temp-dir {:prefix "e2e-test-"}))
          project-path "/my/project"
          session-id "e2e-test-session"
          encoded-path (sessions/encode-project-path project-path)
          session-dir (fs/path tmp-dir encoded-path)]
      (try
        (fs/create-dirs session-dir)
        (let [session-file (fs/path session-dir (str session-id ".jsonl"))
              ;; Realistic session with mixed content
              entries [{:type "user" :message "Read the config file"}
                       {:type "assistant"
                        :message {:content [{:type "text" :text "I'll read that for you."}
                                            {:type "tool_use"
                                             :name "Read"
                                             :id "read_1"
                                             :input {:file_path "/config.edn"}}]}}
                       {:type "tool_result" :tool_use_id "read_1" :content "{}"}
                       {:type "assistant"
                        :message {:content [{:type "text" :text "Now I'll edit it."}
                                            {:type "tool_use"
                                             :name "Edit"
                                             :id "edit_1"
                                             :input {:file_path "/config.edn"}}]}}
                       {:type "tool_result" :tool_use_id "edit_1" :content "done"}
                       {:type "assistant"
                        :message {:content [{:type "tool_use"
                                             :name "mcp__forj__reload_namespace"
                                             :id "reload_1"
                                             :input {:ns "my.ns"}}
                                            {:type "tool_use"
                                             :name "mcp__forj__run_tests"
                                             :id "test_1"
                                             :input {}}]}}]]
          (spit (str session-file)
                (str/join "\n" (map json/generate-string entries)))
          (with-redefs [sessions/claude-projects-dir (constantly tmp-dir)]
            ;; Test read-session-jsonl
            (let [path (sessions/session-log-path session-id project-path)
                  entries-read (sessions/read-session-jsonl path)]
              (is (= 6 (count entries-read))))
            ;; Test extract-tool-calls
            (let [path (sessions/session-log-path session-id project-path)
                  entries-read (sessions/read-session-jsonl path)
                  tool-calls (sessions/extract-tool-calls entries-read)]
              (is (= 4 (count tool-calls)))
              (is (= ["Read" "Edit" "mcp__forj__reload_namespace" "mcp__forj__run_tests"]
                     (map :name tool-calls))))
            ;; Test session-tool-summary
            (let [summary (sessions/session-tool-summary session-id project-path)]
              (is (true? (:exists? summary)))
              (is (= 4 (:total-calls summary)))
              (is (= {"Read" 1 "Edit" 1
                      "mcp__forj__reload_namespace" 1
                      "mcp__forj__run_tests" 1}
                     (:tool-counts summary))))))
        (finally
          (fs/delete-tree tmp-dir))))))

(comment
  ;; Run tests from REPL
  (clojure.test/run-tests 'forj.lisa.claude-sessions-test)
  )
