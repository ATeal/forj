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
            (is (= "/home/user/myproject" (:project-path session)))))
        (finally
          (fs/delete-tree tmp-dir))))))

(deftest claude-decode-project-path-test
  (testing "Decodes encoded project paths"
    (is (= "/home/arteal/Projects/github/forj"
           (claude/decode-project-path "-home-arteal-Projects-github-forj"))))

  (testing "Returns nil for nil input"
    (is (nil? (claude/decode-project-path nil)))))

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

(comment
  ;; Run tests from REPL
  (clojure.test/run-tests 'forj.lisa.sessions-test)
  )
