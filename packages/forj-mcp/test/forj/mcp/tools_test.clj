(ns forj.mcp.tools-test
  "Tests for forj.mcp.tools"
  (:require [clojure.test :refer [deftest testing is are]]
            [babashka.fs :as fs]
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
      (is (contains? tool-names "analyze_project"))
      (is (contains? tool-names "eval_comment_block")))))

;; =============================================================================
;; find-comment-blocks tests
;; =============================================================================

(deftest find-comment-blocks-test
  (testing "Finds comment blocks in content"
    (let [content "(ns test)\n\n(comment\n  (+ 1 2)\n  (* 3 4))\n\n(defn foo [] 1)"
          blocks (#'tools/find-comment-blocks content)]
      (is (= 1 (count blocks)))
      (is (= 3 (:start-line (first blocks))))
      (is (= 5 (:end-line (first blocks))))))

  (testing "Returns empty for no comment blocks"
    (let [content "(ns test)\n(defn foo [] 1)"
          blocks (#'tools/find-comment-blocks content)]
      (is (empty? blocks)))))

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

;; =============================================================================
;; Process Tracking tests - PGID capture, group kill, session reconciliation
;; =============================================================================

(deftest track-process-safety-test
  (testing "Rejects PID 0 (would kill process group)"
    (let [result (tools/track-process {:pid 0 :name "dangerous"})]
      (is (false? (:success result)))
      (is (re-find #"Invalid PID" (:error result)))))

  (testing "Rejects negative PIDs"
    (let [result (tools/track-process {:pid -1 :name "negative"})]
      (is (false? (:success result)))
      (is (re-find #"Invalid PID" (:error result)))))

  (testing "Rejects nil PID"
    (let [result (tools/track-process {:pid nil :name "nil-pid"})]
      (is (false? (:success result)))
      (is (re-find #"Invalid PID" (:error result))))))

(deftest track-process-pgid-capture-test
  (testing "Captures PGID alongside PID for current process"
    ;; Use current process PID which we know exists
    (let [current-pid (-> (java.lang.ProcessHandle/current) .pid)
          result (tools/track-process {:pid current-pid
                                       :name "test-pgid-capture"
                                       :command "test"})]
      (is (:success result))
      (is (= current-pid (get-in result [:tracked :pid])))
      ;; PGID should be captured (may equal PID if process is its own group leader)
      (is (pos-int? (get-in result [:tracked :pgid])))
      ;; Clean up session file directly (don't call stop-project as it would kill us!)
      (let [session-file (#'tools/session-file-path ".")]
        (when (fs/exists? session-file)
          (fs/delete session-file))))))

(deftest list-tracked-processes-pruning-test
  (testing "Auto-prunes dead processes from session"
    ;; Track a fake process with a non-existent PID
    (let [fake-pid 999999999  ; Very high PID unlikely to exist
          session-path "."]
      ;; Write a session with a dead process entry
      (#'tools/write-session session-path
                             {:processes [{:pid fake-pid
                                           :pgid fake-pid
                                           :name "dead-process"
                                           :command "fake"
                                           :started-at "2024-01-01T00:00:00Z"}]
                              :path (str (fs/absolutize session-path))})
      ;; List processes - should detect dead and prune
      (let [result (tools/list-tracked-processes {:path session-path})]
        (is (:success result))
        (is (= 1 (:count result)) "Should report original count")
        (is (= 0 (:alive-count result)) "Dead process should not be alive")
        (is (= 1 (:pruned-count result)) "Should prune the dead process")
        (is (= "dead-process" (-> result :pruned first :name)))))))

(deftest list-tracked-processes-status-test
  (testing "Reports alive status for running processes"
    (let [current-pid (-> (java.lang.ProcessHandle/current) .pid)
          _ (tools/track-process {:pid current-pid
                                  :name "alive-test"
                                  :command "test"})
          result (tools/list-tracked-processes {:path "."})]
      (is (:success result))
      (is (pos? (:alive-count result)))
      ;; Find our process in the list
      (let [our-proc (first (filter #(= "alive-test" (:name %)) (:processes result)))]
        (is (:alive our-proc) "Current process should be reported as alive"))
      ;; Clean up session file directly (don't call stop-project as it would kill us!)
      (let [session-file (#'tools/session-file-path ".")]
        (when (fs/exists? session-file)
          (fs/delete session-file))))))

(deftest stop-project-group-kill-test
  (testing "Uses PGID for killing when available"
    ;; Track current process (won't actually be killed, but tests the logic)
    (let [current-pid (-> (java.lang.ProcessHandle/current) .pid)
          _ (tools/track-process {:pid current-pid
                                  :name "pgid-kill-test"
                                  :command "test"})
          ;; Note: We can't actually test killing the current process,
          ;; but we can verify the session tracking works with PGID
          list-result (tools/list-tracked-processes {:path "."})]
      (is (:success list-result))
      (let [proc (first (filter #(= "pgid-kill-test" (:name %)) (:processes list-result)))]
        (is (pos-int? (:pgid proc)) "PGID should be captured"))
      ;; Clean up session without actually killing
      (let [session-file (#'tools/session-file-path ".")]
        (when (fs/exists? session-file)
          (fs/delete session-file))))))

(deftest stop-project-safety-test
  (testing "Refuses to kill invalid PIDs in session"
    ;; Manually create a session with invalid entries
    (let [session-path "."]
      (#'tools/write-session session-path
                             {:processes [{:pid 0 :name "dangerous-zero" :pgid 0}
                                          {:pid -1 :name "dangerous-negative" :pgid -1}]
                              :path (str (fs/absolutize session-path))})
      (let [result (tools/stop-project {:path session-path})]
        (is (:success result))
        ;; Both should be refused with safety error
        (doseq [r (:results result)]
          (is (false? (:stopped r)))
          (is (re-find #"SAFETY" (:error r))))))))

(deftest stop-project-fallback-test
  (testing "Falls back to PID when PGID kill fails"
    ;; This tests the logic path - we use a dead process so kill will fail
    (let [fake-pid 999999998
          session-path "."]
      (#'tools/write-session session-path
                             {:processes [{:pid fake-pid
                                           :pgid fake-pid
                                           :name "dead-for-fallback"
                                           :command "fake"}]
                              :path (str (fs/absolutize session-path))})
      (let [result (tools/stop-project {:path session-path})]
        (is (:success result))
        ;; Process was dead, so it should report already-dead
        (let [proc-result (first (:results result))]
          (is (:already-dead proc-result)))))))

(deftest stop-project-legacy-pid-only-test
  (testing "Falls back to PID for legacy entries without PGID"
    (let [fake-pid 999999997
          session-path "."]
      ;; Create entry without :pgid (legacy format)
      (#'tools/write-session session-path
                             {:processes [{:pid fake-pid
                                           :name "legacy-no-pgid"
                                           :command "old-style"}]
                              :path (str (fs/absolutize session-path))})
      (let [result (tools/stop-project {:path session-path})]
        (is (:success result))
        ;; Should handle gracefully even without PGID
        (let [proc-result (first (:results result))]
          (is (:already-dead proc-result))
          (is (nil? (:pgid proc-result))))))))

(deftest session-reconciliation-test
  (testing "Mixed alive/dead processes are reconciled correctly"
    (let [current-pid (-> (java.lang.ProcessHandle/current) .pid)
          fake-pid 999999996
          session-path "."]
      ;; Write session with one alive and one dead process
      (#'tools/write-session session-path
                             {:processes [{:pid current-pid
                                           :pgid current-pid
                                           :name "alive-process"
                                           :command "real"}
                                          {:pid fake-pid
                                           :pgid fake-pid
                                           :name "dead-process"
                                           :command "fake"}]
                              :path (str (fs/absolutize session-path))})
      ;; List should prune dead, keep alive
      (let [result (tools/list-tracked-processes {:path session-path})]
        (is (:success result))
        (is (= 2 (:count result)) "Original count should be 2")
        (is (= 1 (:alive-count result)) "One process should be alive")
        (is (= 1 (:pruned-count result)) "One process should be pruned")
        ;; Verify the dead one was pruned from the list
        (is (= "dead-process" (-> result :pruned first :name))))
      ;; Verify session file was updated to only contain alive process
      (let [updated-session (#'tools/read-session session-path)]
        (is (= 1 (count (:processes updated-session))))
        (is (= "alive-process" (-> updated-session :processes first :name))))
      ;; Clean up session file directly (don't call stop-project as it would kill us!)
      (let [session-file (#'tools/session-file-path ".")]
        (when (fs/exists? session-file)
          (fs/delete session-file))))))
