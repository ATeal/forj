(ns forj.lisa.sessions-test
  "Tests for session introspection: opencode-sessions, claude-sessions list-sessions,
   unified sessions interface, and MCP tool handlers."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [forj.lisa.opencode-sessions :as opencode]
            [forj.lisa.claude-sessions :as claude]
            [forj.lisa.sessions :as sessions]
            [forj.mcp.tools :as tools]))

;; =============================================================================
;; Test data
;; =============================================================================

(def ^:private sample-opencode-sessions
  "Mock data matching what opencode/query returns for list-sessions."
  [{:id "ses_001" :title "Fix bug" :directory "/home/user/project"
    :project_name "project" :created 1700000000000 :updated 1700001000000}
   {:id "ses_002" :title "Add feature" :directory "/home/user/other"
    :project_name "other" :created 1700000500000 :updated 1700002000000}])

(def ^:private sample-opencode-messages
  "Mock data for opencode session messages."
  [{:data (json/generate-string {:role "user" :modelID nil :tokens nil :cost nil})
    :time_created 1700000000000 :time_updated 1700000000000}
   {:data (json/generate-string {:role "assistant" :modelID "claude-3-opus" :tokens 500 :cost 0.05})
    :time_created 1700000010000 :time_updated 1700000015000}])

(def ^:private sample-opencode-parts
  "Mock data for opencode session parts."
  [{:id "p1" :session_id "ses_001" :data (json/generate-string {:type "text" :text "Hello"})
    :time_created 1700000000000 :time_updated 1700000000000}
   {:id "p2" :session_id "ses_001"
    :data (json/generate-string {:type "tool" :callID "tc_1" :tool "Read"
                                 :state {:input {:file_path "/foo.clj"} :status "done" :output "contents"}})
    :time_created 1700000005000 :time_updated 1700000006000}
   {:id "p3" :session_id "ses_001"
    :data (json/generate-string {:type "tool" :callID "tc_2" :tool "Edit"
                                 :state {:input {:file_path "/foo.clj"} :status "done" :output "ok"}})
    :time_created 1700000007000 :time_updated 1700000008000}
   {:id "p4" :session_id "ses_001" :data (json/generate-string {:type "text" :text "Done"})
    :time_created 1700000009000 :time_updated 1700000009000}])

;; =============================================================================
;; opencode-sessions tests (with mocked SQLite queries)
;; =============================================================================

(deftest opencode-list-sessions-test
  (testing "Returns sessions with correct keys"
    (with-redefs [opencode/query (fn [sql & _params]
                                   (when (str/includes? sql "FROM session")
                                     sample-opencode-sessions))]
      (let [result (opencode/list-sessions)]
        (is (= 2 (count result)))
        (is (every? #(contains? % :id) result))
        (is (every? #(contains? % :project-name) result))
        ;; project_name should be renamed to project-name
        (is (every? #(not (contains? % :project_name)) result))
        (is (= "ses_001" (:id (first result))))
        (is (= "project" (:project-name (first result)))))))

  (testing "Returns empty vec when DB doesn't exist"
    (with-redefs [opencode/query (fn [& _] nil)]
      (let [result (opencode/list-sessions)]
        (is (or (nil? result) (empty? result)))))))

(deftest opencode-session-messages-test
  (testing "Parses messages with correct keys"
    (with-redefs [opencode/query (fn [sql & _params]
                                   (when (str/includes? sql "FROM message")
                                     sample-opencode-messages))]
      (let [result (opencode/session-messages "ses_001")]
        (is (= 2 (count result)))
        (is (= "user" (:role (first result))))
        (is (= "assistant" (:role (second result))))
        (is (= "claude-3-opus" (:model (second result))))
        (is (= 0.05 (:cost (second result)))))))

  (testing "Returns empty vec for session with no messages"
    (with-redefs [opencode/query (fn [& _] [])]
      (is (empty? (opencode/session-messages "ses_nonexistent"))))))

(deftest opencode-session-parts-test
  (testing "Parses parts with correct types"
    (with-redefs [opencode/query (fn [sql & _params]
                                   (when (str/includes? sql "FROM part")
                                     sample-opencode-parts))]
      (let [result (opencode/session-parts "ses_001")]
        (is (= 4 (count result)))
        (is (= "text" (:type (first result))))
        (is (= "tool" (:type (second result))))
        (is (= "Read" (:tool (second result))))
        (is (= "tc_1" (:call-id (second result))))))))

(deftest opencode-extract-tool-calls-test
  (testing "Extracts tool calls matching claude-sessions shape"
    (with-redefs [opencode/query (fn [sql & _params]
                                   (when (str/includes? sql "FROM part")
                                     sample-opencode-parts))]
      (let [parts (opencode/session-parts "ses_001")
            tool-calls (opencode/extract-tool-calls parts)]
        (is (= 2 (count tool-calls)))
        ;; Must have :name :input :id keys (same as claude-sessions)
        (is (every? #(contains? % :name) tool-calls))
        (is (every? #(contains? % :input) tool-calls))
        (is (every? #(contains? % :id) tool-calls))
        (is (= "Read" (:name (first tool-calls))))
        (is (= "Edit" (:name (second tool-calls)))))))

  (testing "Returns empty vec for no tool parts"
    (let [text-parts [{:type "text" :text "hello"}]]
      (is (empty? (opencode/extract-tool-calls text-parts))))))

(deftest opencode-tool-call-counts-test
  (testing "Counts and sorts tool calls"
    (let [tool-calls [{:name "Read"} {:name "Read"} {:name "Edit"}]
          result (opencode/tool-call-counts tool-calls)]
      (is (= {"Read" 2 "Edit" 1} result))
      ;; Sorted by count descending
      (is (= ["Read" "Edit"] (keys result)))))

  (testing "Returns empty map for empty input"
    (is (empty? (opencode/tool-call-counts [])))))

(deftest opencode-session-summary-test
  (testing "Returns summary for existing session with parts"
    (with-redefs [opencode/query (fn [sql & params]
                                   (cond
                                     (str/includes? sql "FROM part")
                                     sample-opencode-parts

                                     (str/includes? sql "FROM message")
                                     [{:id "m1" :data (json/generate-string {:role "user"})}
                                      {:id "m2" :data (json/generate-string {:role "assistant"})}]

                                     :else nil))]
      (let [result (opencode/session-summary "ses_001")]
        (is (true? (:exists? result)))
        (is (= "ses_001" (:id result)))
        (is (= 2 (:total-calls result)))
        (is (pos? (:turn-count result)))
        (is (map? (:tool-counts result))))))

  (testing "Returns exists? false for non-existent session"
    (with-redefs [opencode/query (fn [& _] [])]
      (let [result (opencode/session-summary "ses_fake")]
        (is (false? (:exists? result)))
        (is (= "ses_fake" (:id result)))))))

;; =============================================================================
;; claude-sessions list-sessions tests
;; =============================================================================

(defn- create-test-claude-dir
  "Create a temp dir structure simulating ~/.claude/projects/."
  []
  (let [tmp-dir (fs/create-temp-dir {:prefix "claude-sessions-test-"})
        project-dir (fs/path tmp-dir "-home-user-myproject")]
    (fs/create-dirs project-dir)
    {:tmp-dir tmp-dir :project-dir project-dir}))

(defn- write-session-file
  "Write a test JSONL session file."
  [dir session-id entries]
  (let [file-path (fs/path dir (str session-id ".jsonl"))]
    (spit (str file-path) (str/join "\n" (map json/generate-string entries)))
    file-path))

(deftest claude-list-sessions-test
  (testing "Lists sessions from project directories"
    (let [{:keys [tmp-dir project-dir]} (create-test-claude-dir)]
      (try
        (write-session-file project-dir "session-aaa"
                            [{:type "user" :message "hi" :timestamp "2026-02-25T10:00:00Z"}])
        (write-session-file project-dir "session-bbb"
                            [{:type "user" :message "hello" :timestamp "2026-02-25T11:00:00Z"}])
        (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
          (let [result (claude/list-sessions)]
            (is (= 2 (count result)))
            ;; Check keys
            (is (every? #(contains? % :id) result))
            (is (every? #(contains? % :directory) result))
            (is (every? #(contains? % :project-path) result))
            (is (every? #(contains? % :created) result))
            (is (every? #(contains? % :updated) result))
            (is (every? #(contains? % :size-bytes) result))
            ;; Sessions should contain our IDs
            (is (= #{"session-aaa" "session-bbb"} (set (map :id result))))
            ;; Sorted by updated desc
            (is (>= (:updated (first result)) (:updated (second result))))))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "Skips subagents directories"
    (let [{:keys [tmp-dir project-dir]} (create-test-claude-dir)
          subagent-dir (fs/path project-dir "subagents")]
      (try
        (fs/create-dirs subagent-dir)
        (write-session-file project-dir "normal-session"
                            [{:type "user" :timestamp "2026-02-25T10:00:00Z"}])
        (write-session-file subagent-dir "subagent-session"
                            [{:type "user" :timestamp "2026-02-25T10:00:00Z"}])
        (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
          (let [result (claude/list-sessions)]
            (is (= 1 (count result)))
            (is (= "normal-session" (:id (first result))))))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "Returns empty for non-existent projects dir"
    (with-redefs [claude/claude-projects-dir (constantly "/nonexistent/path")]
      (let [result (claude/list-sessions)]
        (is (or (nil? result) (empty? result))))))

  (testing "Decodes project path from directory name"
    (let [{:keys [tmp-dir project-dir]} (create-test-claude-dir)]
      (try
        (write-session-file project-dir "test-session"
                            [{:type "user" :timestamp "2026-02-25T10:00:00Z"}])
        (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
          (let [result (claude/list-sessions)
                session (first result)]
            (is (= "-home-user-myproject" (:directory session)))
            ;; project-path is decoded from directory - starts with /
            ;; Exact result depends on filesystem (smart decode checks real dirs)
            (is (string? (:project-path session)))
            (is (str/starts-with? (:project-path session) "/"))))
        (finally
          (fs/delete-tree tmp-dir))))))

(deftest claude-decode-project-path-test
  (testing "Decodes encoded project paths"
    (is (= "/home/arteal/Projects/github/forj"
           (claude/decode-project-path "-home-arteal-Projects-github-forj"))))

  (testing "Returns nil for nil input"
    (is (nil? (claude/decode-project-path nil))))

  (testing "Preserves hyphens in project names when path exists on disk"
    ;; Create a real directory structure to test smart decoding
    (let [tmp-dir (fs/create-temp-dir {:prefix "decode-test-"})
          ;; Simulate /tmp/xxx/my-project existing
          parent (fs/path tmp-dir "parent")
          project (fs/path parent "my-project")]
      (try
        (fs/create-dirs project)
        ;; Encode as if it were: /tmp/xxx/parent/my-project
        ;; The encoded form replaces / with -
        (let [encoded (claude/encode-project-path (str project))
              decoded (claude/decode-project-path encoded)]
          ;; Smart decode should find the real path with hyphens preserved
          (is (= (str project) decoded)))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "Falls back gracefully for non-existent paths"
    ;; For a path that doesn't exist on disk, should still produce a result
    (let [result (claude/decode-project-path "-nonexistent-path-with-hyphens")]
      (is (some? result))
      (is (string? result)))))

(deftest claude-extract-transcript-test
  (testing "Extracts user and assistant turns"
    (let [entries [{:type "user"
                    :message {:content "What is 2+2?"}}
                   {:type "assistant"
                    :message {:content [{:type "text" :text "The answer is 4."}]}}]
          result (claude/extract-transcript entries)]
      (is (= 2 (count result)))
      (is (= "user" (:type (first result))))
      (is (= "assistant" (:type (second result))))
      (is (str/includes? (:content (second result)) "answer is 4"))))

  (testing "Includes tool calls in assistant turns"
    (let [entries [{:type "assistant"
                    :message {:content [{:type "text" :text "I'll read that."}
                                        {:type "tool_use" :name "Read" :id "t1"
                                         :input {:file_path "/foo.clj"}}]}}]
          result (claude/extract-transcript entries)]
      (is (= 1 (count result)))
      (is (= 1 (count (:tool_calls (first result)))))
      (is (= "Read" (:tool (first (:tool_calls (first result))))))))

  (testing "Truncates long content"
    (let [long-text (apply str (repeat 600 "x"))
          entries [{:type "user" :message {:content long-text}}]
          result (claude/extract-transcript entries)]
      (is (<= (count (:content (first result))) 504)))))

;; =============================================================================
;; Unified sessions interface tests
;; =============================================================================

;; =============================================================================
;; Tool name normalization tests (refinement #2)
;; =============================================================================

(deftest normalize-tool-name-test
  (testing "Lowercases PascalCase tool names"
    (is (= "read" (sessions/normalize-tool-name "Read")))
    (is (= "bash" (sessions/normalize-tool-name "Bash")))
    (is (= "glob" (sessions/normalize-tool-name "Glob"))))

  (testing "Keeps already lowercase names unchanged"
    (is (= "read" (sessions/normalize-tool-name "read")))
    (is (= "bash" (sessions/normalize-tool-name "bash"))))

  (testing "Handles MCP-style names"
    (is (= "mcp__forj__repl_eval" (sessions/normalize-tool-name "mcp__forj__repl_eval"))))

  (testing "Returns nil for nil input"
    (is (nil? (sessions/normalize-tool-name nil)))))

(deftest session-summary-normalizes-tool-counts-test
  (testing "Claude CLI tool counts are normalized to lowercase"
    (let [{:keys [tmp-dir project-dir]} (create-test-claude-dir)]
      (try
        (let [entries [{:type "user" :message {:content "hi"}
                        :timestamp "2026-02-25T10:00:00Z"}
                       {:type "assistant"
                        :message {:content [{:type "tool_use" :name "Read" :id "t1"
                                             :input {:file_path "/foo.clj"}}
                                            {:type "tool_use" :name "Edit" :id "t2"
                                             :input {:file_path "/foo.clj"}}]}
                        :timestamp "2026-02-25T10:01:00Z"}]
              _ (write-session-file project-dir "test-norm" entries)]
          (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
            (let [result (sessions/session-summary
                           {:id "test-norm" :source :claude-cli
                            :directory "/home/user/myproject"})]
              ;; Tool names should be lowercase
              (is (contains? (:tool-counts result) "read"))
              (is (contains? (:tool-counts result) "edit"))
              (is (not (contains? (:tool-counts result) "Read")))
              (is (not (contains? (:tool-counts result) "Edit"))))))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "OpenCode tool counts are normalized to lowercase"
    (with-redefs [opencode/session-summary (constantly {:id "ses_x" :exists? true
                                                         :tool-counts {"read" 2 "bash" 1}
                                                         :total-calls 3
                                                         :turn-count 4})
                  opencode/session-messages (constantly [{:role "user" :created 100 :updated 100}])]
      (let [result (sessions/session-summary {:id "ses_x" :source :opencode})]
        ;; Already lowercase but should still work through normalization
        (is (contains? (:tool-counts result) "read"))
        (is (contains? (:tool-counts result) "bash"))))))

;; =============================================================================
;; Derived Claude titles tests (refinement #3)
;; =============================================================================

(deftest claude-extract-user-title-test
  (testing "Extracts title from first user message with string content"
    (let [entries [{:type "user" :message {:content "Fix the login bug"}}
                   {:type "assistant" :message {:content [{:type "text" :text "Sure"}]}}]]
      (is (= "Fix the login bug" (#'claude/extract-user-title entries)))))

  (testing "Extracts title from first user message with array content"
    (let [entries [{:type "user" :message {:content [{:type "text" :text "Add feature X"}]}}]]
      (is (= "Add feature X" (#'claude/extract-user-title entries)))))

  (testing "Truncates long titles to 80 chars"
    (let [long-msg (apply str (repeat 100 "x"))
          entries [{:type "user" :message {:content long-msg}}]
          result (#'claude/extract-user-title entries)]
      (is (<= (count result) 80))
      (is (str/ends-with? result "..."))))

  (testing "Returns nil when no user messages"
    (let [entries [{:type "assistant" :message {:content [{:type "text" :text "hi"}]}}]]
      (is (nil? (#'claude/extract-user-title entries)))))

  (testing "Returns nil for empty entries"
    (is (nil? (#'claude/extract-user-title [])))
    (is (nil? (#'claude/extract-user-title nil)))))

(deftest claude-list-sessions-title-is-nil-test
  (testing "list-sessions returns :title nil (performance optimization)"
    (let [{:keys [tmp-dir project-dir]} (create-test-claude-dir)]
      (try
        (write-session-file project-dir "test-title"
                            [{:type "user" :message {:content "Hello world"}
                              :timestamp "2026-02-25T10:00:00Z"}])
        (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
          (let [result (claude/list-sessions)
                session (first result)]
            ;; Title should be nil in list-sessions (perf optimization)
            (is (nil? (:title session)))
            ;; But key should still exist
            (is (contains? session :title))))
        (finally
          (fs/delete-tree tmp-dir))))))

;; =============================================================================
;; OpenCode project-name fallback tests (refinement #4)
;; =============================================================================

(deftest opencode-project-name-fallback-test
  (testing "Falls back to directory-derived project name when project_name is nil"
    (let [sessions-with-nil-project
          [{:id "ses_003" :title "Test" :directory "/home/user/my-project"
            :project_name nil :created 1700000000000 :updated 1700001000000}]]
      (with-redefs [opencode/query (fn [sql & _]
                                     (when (str/includes? sql "FROM session")
                                       sessions-with-nil-project))]
        (let [result (opencode/list-sessions)]
          (is (= 1 (count result)))
          ;; Should derive "my-project" from directory
          (is (= "my-project" (:project-name (first result))))))))

  (testing "Falls back to directory-derived name when project_name is empty string"
    (let [sessions-with-empty-project
          [{:id "ses_004" :title "Test" :directory "/home/user/another-proj"
            :project_name "" :created 1700000000000 :updated 1700001000000}]]
      (with-redefs [opencode/query (fn [sql & _]
                                     (when (str/includes? sql "FROM session")
                                       sessions-with-empty-project))]
        (let [result (opencode/list-sessions)]
          (is (= "another-proj" (:project-name (first result))))))))

  (testing "Uses project_name when available"
    (with-redefs [opencode/query (fn [sql & _]
                                   (when (str/includes? sql "FROM session")
                                     sample-opencode-sessions))]
      (let [result (opencode/list-sessions)]
        (is (= "project" (:project-name (first result))))))))

;; =============================================================================
;; Unified sessions interface tests
;; =============================================================================

(deftest normalize-claude-session-test
  (testing "Normalizes claude session to common shape"
    (let [raw {:id "abc-123"
               :directory "-home-user-project"
               :project-path "/home/user/project"
               :created 1700000000000
               :updated 1700001000000
               :size-bytes 12345}
          result (sessions/normalize-claude-session raw)]
      (is (= "abc-123" (:id result)))
      (is (= :claude-cli (:source result)))
      (is (nil? (:title result)))
      (is (= "/home/user/project" (:directory result)))
      (is (= "project" (:project-name result)))
      (is (= "Claude CLI" (:client-label result)))
      (is (= sessions/common-keys (set (keys result)))))))

(deftest normalize-opencode-session-test
  (testing "Normalizes opencode session to common shape"
    (let [raw {:id "ses_xyz"
               :title "Test session"
               :directory "/home/user/project"
               :project-name "project"
               :created 1700000000000
               :updated 1700001000000}
          result (sessions/normalize-opencode-session raw)]
      (is (= "ses_xyz" (:id result)))
      (is (= :opencode (:source result)))
      (is (= "Test session" (:title result)))
      (is (= "OpenCode" (:client-label result)))
      (is (= sessions/common-keys (set (keys result)))))))

(deftest normalizers-produce-same-keys-test
  (testing "Both normalizers produce identical key sets"
    (let [claude-keys (set (keys (sessions/normalize-claude-session
                                   {:id "c" :directory "d" :project-path "/p"
                                    :created 0 :updated 0 :size-bytes 0})))
          opencode-keys (set (keys (sessions/normalize-opencode-session
                                     {:id "o" :title "t" :directory "/p"
                                      :project-name "p" :created 0 :updated 0})))]
      (is (= claude-keys opencode-keys sessions/common-keys)))))

(deftest list-recent-sessions-test
  (let [mock-claude-sessions [{:id "c1" :directory "-home-user-proj"
                                :project-path "/home/user/proj"
                                :created 1700000000000 :updated 1700003000000
                                :size-bytes 100}
                               {:id "c2" :directory "-home-user-other"
                                :project-path "/home/user/other"
                                :created 1700000000000 :updated 1700001000000
                                :size-bytes 200}]
        mock-opencode-sessions [{:id "o1" :title "OC Session"
                                  :directory "/home/user/proj"
                                  :project-name "proj"
                                  :created 1700000000000 :updated 1700002000000}]]

    (testing "Merges and sorts sessions from both sources"
      (with-redefs [claude/list-sessions (constantly mock-claude-sessions)
                    opencode/list-sessions (constantly mock-opencode-sessions)]
        (let [result (sessions/list-recent-sessions)]
          (is (= 3 (count result)))
          ;; Sorted by updated desc
          (is (= ["c1" "o1" "c2"] (map :id result)))
          ;; All have common keys
          (is (every? #(= sessions/common-keys (set (keys %))) result)))))

    (testing "Filters by client"
      (with-redefs [claude/list-sessions (constantly mock-claude-sessions)
                    opencode/list-sessions (constantly mock-opencode-sessions)]
        (let [claude-only (sessions/list-recent-sessions {:client :claude-cli})
              opencode-only (sessions/list-recent-sessions {:client :opencode})]
          (is (= 2 (count claude-only)))
          (is (every? #(= :claude-cli (:source %)) claude-only))
          (is (= 1 (count opencode-only)))
          (is (every? #(= :opencode (:source %)) opencode-only)))))

    (testing "Filters by project substring"
      (with-redefs [claude/list-sessions (constantly mock-claude-sessions)
                    opencode/list-sessions (constantly mock-opencode-sessions)]
        (let [result (sessions/list-recent-sessions {:project "proj"})]
          ;; "proj" matches "/home/user/proj" but not "/home/user/other"
          (is (= 2 (count result)))
          (is (every? #(str/includes? (str/lower-case (str (:directory %))) "proj") result)))))

    (testing "Filters by since"
      (with-redefs [claude/list-sessions (constantly mock-claude-sessions)
                    opencode/list-sessions (constantly mock-opencode-sessions)]
        (let [result (sessions/list-recent-sessions {:since 1700002500000})]
          ;; Only c1 has updated > 1700002500000
          (is (= 1 (count result)))
          (is (= "c1" (:id (first result)))))))

    (testing "Respects limit"
      (with-redefs [claude/list-sessions (constantly mock-claude-sessions)
                    opencode/list-sessions (constantly mock-opencode-sessions)]
        (let [result (sessions/list-recent-sessions {:limit 2})]
          (is (= 2 (count result))))))

    (testing "Handles errors from one source gracefully"
      (with-redefs [claude/list-sessions (fn [] (throw (Exception. "boom")))
                    opencode/list-sessions (constantly mock-opencode-sessions)]
        (let [result (sessions/list-recent-sessions)]
          ;; Should still return opencode sessions
          (is (= 1 (count result)))
          (is (= :opencode (:source (first result)))))))))

(deftest session-summary-test
  (testing "Delegates to claude-cli source"
    (let [{:keys [tmp-dir project-dir]} (create-test-claude-dir)]
      (try
        (let [entries [{:type "user" :message {:content "hi"}
                        :timestamp "2026-02-25T10:00:00Z"}
                       {:type "assistant"
                        :message {:content [{:type "text" :text "hello"}]
                                  :model "claude-3-opus"}
                        :timestamp "2026-02-25T10:01:00Z"}]
              _ (write-session-file project-dir "test-summary" entries)]
          (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
            (let [result (sessions/session-summary
                           {:id "test-summary" :source :claude-cli
                            :directory "/home/user/myproject"})]
              (is (= "test-summary" (:id result)))
              (is (= :claude-cli (:source result)))
              (is (number? (:turn-count result))))))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "Delegates to opencode source"
    (with-redefs [opencode/session-summary (constantly {:id "ses_x" :exists? true
                                                         :tool-counts {"Read" 1} :total-calls 1
                                                         :turn-count 2})
                  opencode/session-messages (constantly [{:role "user" :created 100 :updated 100}
                                                         {:role "assistant" :model "gpt-4"
                                                          :cost 0.01 :created 100 :updated 200}])]
      (let [result (sessions/session-summary {:id "ses_x" :source :opencode})]
        (is (= "ses_x" (:id result)))
        (is (= :opencode (:source result)))
        (is (= 1 (:total-calls result))))))

  (testing "Returns exists? false for non-existent session"
    (with-redefs [opencode/session-summary (constantly {:id "fake" :exists? false})
                  opencode/session-messages (constantly [])]
      (let [result (sessions/session-summary {:id "fake" :source :opencode})]
        (is (false? (:exists? result))))))

  (testing "Throws for unknown source"
    (is (thrown? Exception
                (sessions/session-summary {:id "x" :source :unknown})))))

;; =============================================================================
;; MCP Tool handler tests
;; =============================================================================

(deftest tool-definitions-include-session-tools-test
  (testing "Session tools are defined in tools vec"
    (let [tool-names (set (map :name tools/tools))]
      (is (contains? tool-names "list_sessions"))
      (is (contains? tool-names "session_summary"))))

  (testing "list_sessions tool has correct schema"
    (let [tool (first (filter #(= "list_sessions" (:name %)) tools/tools))]
      (is (some? tool))
      (is (string? (:description tool)))
      (is (get-in tool [:inputSchema :properties :project]))
      (is (get-in tool [:inputSchema :properties :since]))
      (is (get-in tool [:inputSchema :properties :client]))
      (is (get-in tool [:inputSchema :properties :limit]))))

  (testing "session_summary tool has required fields"
    (let [tool (first (filter #(= "session_summary" (:name %)) tools/tools))]
      (is (some? tool))
      (is (= ["id" "source"] (get-in tool [:inputSchema :required]))))))

(deftest list-sessions-handler-test
  (testing "Returns sessions successfully"
    (with-redefs [sessions/list-recent-sessions
                  (constantly [{:id "s1" :source :claude-cli :title nil
                                :directory "/proj" :project-name "proj"
                                :created 100 :updated 200 :client-label "Claude CLI"}])]
      (let [result (tools/call-tool {:name "list_sessions" :arguments {}})]
        (is (true? (:success result)))
        (is (= 1 (:count result)))
        (is (= 1 (count (:sessions result)))))))

  (testing "Passes filter parameters"
    (let [captured-opts (atom nil)]
      (with-redefs [sessions/list-recent-sessions
                    (fn [opts]
                      (reset! captured-opts opts)
                      [])]
        (tools/call-tool {:name "list_sessions"
                          :arguments {:project "myproj"
                                      :client "claude-cli"
                                      :limit 5}})
        (is (= "myproj" (:project @captured-opts)))
        (is (= :claude-cli (:client @captured-opts)))
        (is (= 5 (:limit @captured-opts))))))

  (testing "Parses since parameter as ISO-8601"
    (let [captured-opts (atom nil)]
      (with-redefs [sessions/list-recent-sessions
                    (fn [opts]
                      (reset! captured-opts opts)
                      [])]
        (tools/call-tool {:name "list_sessions"
                          :arguments {:since "2026-02-25T00:00:00Z"}})
        ;; since should be converted to epoch ms
        (is (number? (:since @captured-opts)))
        (is (pos? (:since @captured-opts))))))

  (testing "Returns error for invalid since date"
    (let [result (tools/call-tool {:name "list_sessions"
                                    :arguments {:since "not-a-date"}})]
      (is (false? (:success result)))
      (is (str/includes? (:error result) "Invalid ISO-8601")))))

(deftest session-summary-handler-test
  (testing "Returns summary successfully"
    (with-redefs [sessions/session-summary
                  (constantly {:id "s1" :source :opencode
                               :tool-counts {"Read" 3} :total-calls 3
                               :turn-count 5 :duration-ms 60000
                               :cost 0.10 :model "claude-3-opus"})]
      (let [result (tools/call-tool {:name "session_summary"
                                      :arguments {:id "s1" :source "opencode"}})]
        (is (true? (:success result)))
        (is (= "s1" (:id result)))
        (is (= 3 (:total-calls result))))))

  (testing "Returns error for missing id"
    (let [result (tools/call-tool {:name "session_summary"
                                    :arguments {:source "opencode"}})]
      (is (false? (:success result)))
      (is (str/includes? (:error result) "id"))))

  (testing "Returns error for missing source"
    (let [result (tools/call-tool {:name "session_summary"
                                    :arguments {:id "s1"}})]
      (is (false? (:success result)))
      (is (str/includes? (:error result) "source"))))

  (testing "Passes directory for claude-cli source"
    (let [captured-args (atom nil)]
      (with-redefs [sessions/session-summary
                    (fn [args]
                      (reset! captured-args args)
                      {:id "s1" :source :claude-cli})]
        (tools/call-tool {:name "session_summary"
                          :arguments {:id "s1" :source "claude-cli"
                                      :directory "/my/project"}})
        (is (= "/my/project" (:directory @captured-args)))
        (is (= :claude-cli (:source @captured-args)))))))

;; =============================================================================
;; session_transcript tool tests (refinement #5)
;; =============================================================================

(deftest tool-definitions-include-transcript-test
  (testing "session_transcript tool is defined in tools vec"
    (let [tool-names (set (map :name tools/tools))]
      (is (contains? tool-names "session_transcript"))))

  (testing "session_transcript tool has correct schema"
    (let [tool (first (filter #(= "session_transcript" (:name %)) tools/tools))]
      (is (some? tool))
      (is (string? (:description tool)))
      (is (= ["id" "source"] (get-in tool [:inputSchema :required])))
      (is (get-in tool [:inputSchema :properties :id]))
      (is (get-in tool [:inputSchema :properties :source]))
      (is (get-in tool [:inputSchema :properties :directory]))
      (is (get-in tool [:inputSchema :properties :limit])))))

(deftest session-transcript-unified-test
  (testing "Returns claude-cli transcript"
    (let [{:keys [tmp-dir project-dir]} (create-test-claude-dir)]
      (try
        (let [entries [{:type "user" :message {:content "What is 2+2?"}
                        :timestamp "2026-02-25T10:00:00Z"}
                       {:type "assistant"
                        :message {:content [{:type "text" :text "The answer is 4."}]}
                        :timestamp "2026-02-25T10:01:00Z"}]
              _ (write-session-file project-dir "test-transcript" entries)]
          (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
            (let [result (sessions/session-transcript
                           {:id "test-transcript" :source :claude-cli
                            :directory "/home/user/myproject"})]
              (is (true? (:exists? result)))
              (is (= :claude-cli (:source result)))
              (is (= 2 (:turn-count result)))
              (is (= 2 (count (:transcript result))))
              (is (= "user" (:type (first (:transcript result)))))
              (is (= "assistant" (:type (second (:transcript result))))))))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "Returns exists? false for non-existent claude-cli session"
    (let [{:keys [tmp-dir]} (create-test-claude-dir)]
      (try
        (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
          (let [result (sessions/session-transcript
                         {:id "nonexistent" :source :claude-cli
                          :directory "/home/user/myproject"})]
            (is (false? (:exists? result)))))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "Respects limit parameter"
    (let [{:keys [tmp-dir project-dir]} (create-test-claude-dir)]
      (try
        (let [entries (vec (for [i (range 10)]
                            {:type (if (even? i) "user" "assistant")
                             :message {:content (if (even? i)
                                                  (str "Question " i)
                                                  [{:type "text" :text (str "Answer " i)}])}
                             :timestamp (str "2026-02-25T10:0" i ":00Z")}))
              _ (write-session-file project-dir "test-limit" entries)]
          (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
            (let [result (sessions/session-transcript
                           {:id "test-limit" :source :claude-cli
                            :directory "/home/user/myproject"
                            :limit 3})]
              (is (true? (:exists? result)))
              ;; Should return only last 3 turns
              (is (= 3 (count (:transcript result)))))))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "Returns opencode transcript"
    (with-redefs [opencode/extract-transcript
                  (constantly [{:type "user" :content "hello"}
                               {:type "assistant" :content "hi there"}])]
      (let [result (sessions/session-transcript {:id "ses_001" :source :opencode})]
        (is (true? (:exists? result)))
        (is (= :opencode (:source result)))
        (is (= 2 (:turn-count result))))))

  (testing "Returns exists? false for empty opencode transcript"
    (with-redefs [opencode/extract-transcript (constantly [])]
      (let [result (sessions/session-transcript {:id "ses_fake" :source :opencode})]
        (is (false? (:exists? result))))))

  (testing "Throws for unknown source"
    (is (thrown? Exception
                (sessions/session-transcript {:id "x" :source :unknown})))))

(deftest session-transcript-handler-test
  (testing "Returns transcript successfully"
    (with-redefs [sessions/session-transcript
                  (constantly {:id "s1" :source :claude-cli :exists? true
                               :transcript [{:type "user" :content "hi"}]
                               :turn-count 1})]
      (let [result (tools/call-tool {:name "session_transcript"
                                      :arguments {:id "s1" :source "claude-cli"}})]
        (is (true? (:success result)))
        (is (= "s1" (:id result)))
        (is (= 1 (:turn-count result)))
        (is (= 1 (count (:transcript result)))))))

  (testing "Returns error for missing id"
    (let [result (tools/call-tool {:name "session_transcript"
                                    :arguments {:source "opencode"}})]
      (is (false? (:success result)))
      (is (str/includes? (:error result) "id"))))

  (testing "Returns error for missing source"
    (let [result (tools/call-tool {:name "session_transcript"
                                    :arguments {:id "s1"}})]
      (is (false? (:success result)))
      (is (str/includes? (:error result) "source"))))

  (testing "Passes directory and limit parameters"
    (let [captured-args (atom nil)]
      (with-redefs [sessions/session-transcript
                    (fn [args]
                      (reset! captured-args args)
                      {:id "s1" :source :claude-cli :exists? true
                       :transcript [] :turn-count 0})]
        (tools/call-tool {:name "session_transcript"
                          :arguments {:id "s1" :source "claude-cli"
                                      :directory "/my/project"
                                      :limit 10}})
        (is (= "/my/project" (:directory @captured-args)))
        (is (= :claude-cli (:source @captured-args)))
        (is (= 10 (:limit @captured-args)))))))

;; =============================================================================
;; list-sessions performance tests (refinement #6)
;; =============================================================================

(deftest claude-list-sessions-uses-filesystem-metadata-test
  (testing "list-sessions uses file creation/modification time, not file content"
    (let [{:keys [tmp-dir project-dir]} (create-test-claude-dir)]
      (try
        ;; Write a session file with NO timestamp in the content
        (write-session-file project-dir "test-perf"
                            [{:type "user" :message {:content "no timestamp here"}}])
        (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
          (let [result (claude/list-sessions)
                session (first result)]
            ;; Should still have :created and :updated from filesystem metadata
            (is (some? (:created session)))
            (is (some? (:updated session)))
            (is (number? (:created session)))
            (is (number? (:updated session)))
            (is (pos? (:created session)))
            (is (pos? (:updated session)))
            ;; Title should be nil (not derived in list-sessions for perf)
            (is (nil? (:title session)))))
        (finally
          (fs/delete-tree tmp-dir)))))

  (testing "list-sessions includes size-bytes from filesystem"
    (let [{:keys [tmp-dir project-dir]} (create-test-claude-dir)]
      (try
        (write-session-file project-dir "test-size"
                            [{:type "user" :message {:content "some content"}}])
        (with-redefs [claude/claude-projects-dir (constantly (str tmp-dir))]
          (let [result (claude/list-sessions)
                session (first result)]
            (is (number? (:size-bytes session)))
            (is (pos? (:size-bytes session)))))
        (finally
          (fs/delete-tree tmp-dir))))))

(comment
  ;; Run tests from REPL
  (clojure.test/run-tests 'forj.lisa.sessions-test)
  )
