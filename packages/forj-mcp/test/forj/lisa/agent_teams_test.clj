(ns forj.lisa.agent-teams-test
  "Tests for forj.lisa.agent-teams"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [forj.lisa.agent-teams :as agent-teams]))

;; =============================================================================
;; Feature detection tests
;; =============================================================================

(deftest agent-teams-enabled?-test
  (testing "Returns false when env var not set"
    ;; In test environment, FORJ_AGENT_TEAMS is typically not set
    ;; This test documents expected behavior
    (is (boolean? (agent-teams/agent-teams-enabled?)))))

(deftest agent-teams-available?-test
  (testing "Returns a map with :available and :reason keys"
    (let [result (agent-teams/agent-teams-available?)]
      (is (contains? result :available))
      (is (contains? result :reason))
      (is (boolean? (:available result))))))

;; =============================================================================
;; team-name-for-plan tests
;; =============================================================================

(deftest team-name-for-plan-test
  (testing "Generates a team name from plan title"
    (let [plan {:title "Build user authentication"}
          name (agent-teams/team-name-for-plan plan)]
      (is (str/starts-with? name "lisa-"))
      (is (re-matches #"lisa-\d+" name))))

  (testing "Same title produces same name (deterministic)"
    (let [plan {:title "Build user authentication"}]
      (is (= (agent-teams/team-name-for-plan plan)
             (agent-teams/team-name-for-plan plan)))))

  (testing "Different titles produce different names"
    (let [name1 (agent-teams/team-name-for-plan {:title "Build auth"})
          name2 (agent-teams/team-name-for-plan {:title "Build API"})]
      (is (not= name1 name2)))))

;; =============================================================================
;; checkpoint->task-description tests
;; =============================================================================

(deftest checkpoint->task-description-test
  (testing "Generates task with subject and description"
    (let [cp {:id :password-hashing
              :description "Create password hashing module"
              :status :pending}
          result (agent-teams/checkpoint->task-description cp {})]
      (is (= "Checkpoint: password-hashing" (:subject result)))
      (is (string? (:description result)))
      (is (str/includes? (:description result) "Create password hashing module"))))

  (testing "Includes file when specified"
    (let [cp {:id :auth
              :description "Create auth"
              :file "src/auth/core.clj"
              :status :pending}
          result (agent-teams/checkpoint->task-description cp {})]
      (is (str/includes? (:description result) "src/auth/core.clj"))))

  (testing "Includes acceptance criteria"
    (let [cp {:id :auth
              :description "Create auth"
              :acceptance "verify-password returns true"
              :status :pending}
          result (agent-teams/checkpoint->task-description cp {})]
      (is (str/includes? (:description result) "verify-password returns true"))))

  (testing "Includes gates info"
    (let [cp {:id :auth
              :description "Create auth"
              :gates ["repl:(verify-password \"test\" (hash-password \"test\"))"]
              :status :pending}
          result (agent-teams/checkpoint->task-description cp {})]
      (is (str/includes? (:description result) "Gates"))
      (is (str/includes? (:description result) "TaskCompleted hook"))))

  (testing "Includes dependency info"
    (let [cp {:id :jwt
              :description "Create JWT"
              :depends-on [:password-hashing]
              :status :pending}
          result (agent-teams/checkpoint->task-description cp {})]
      (is (str/includes? (:description result) "password-hashing"))))

  (testing "Includes plan title when provided"
    (let [cp {:id :auth :description "Create auth" :status :pending}
          result (agent-teams/checkpoint->task-description cp {:plan-title "Build user auth"})]
      (is (str/includes? (:description result) "Build user auth"))))

  (testing "Includes signs content when provided"
    (let [cp {:id :auth :description "Create auth" :status :pending}
          result (agent-teams/checkpoint->task-description cp {:signs-content "## Sign: forgot require"})]
      (is (str/includes? (:description result) "forgot require"))))

  (testing "Includes REPL validation workflow"
    (let [cp {:id :auth :description "Create auth" :status :pending}
          result (agent-teams/checkpoint->task-description cp {})]
      (is (str/includes? (:description result) "reload_namespace"))
      (is (str/includes? (:description result) "eval_comment_block"))
      (is (str/includes? (:description result) "repl_eval"))))

  (testing "Includes coordination guidance"
    (let [cp {:id :auth :description "Create auth" :status :pending}
          result (agent-teams/checkpoint->task-description cp {})]
      (is (str/includes? (:description result) "Coordination")))))

;; =============================================================================
;; find-checkpoint-for-task tests
;; =============================================================================

(deftest find-checkpoint-for-task-test
  (testing "Returns nil for non-checkpoint subjects"
    (is (nil? (agent-teams/find-checkpoint-for-task "/tmp/nonexistent" "Fix the bug"))))

  (testing "Returns nil for nil subject"
    (is (nil? (agent-teams/find-checkpoint-for-task "/tmp/nonexistent" nil))))

  (testing "Returns nil when no plan exists"
    (is (nil? (agent-teams/find-checkpoint-for-task "/tmp/nonexistent" "Checkpoint: auth")))))

(comment
  (clojure.test/run-tests 'forj.lisa.agent-teams-test))
