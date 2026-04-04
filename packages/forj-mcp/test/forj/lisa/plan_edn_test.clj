(ns forj.lisa.plan-edn-test
  "Tests for forj.lisa.plan-edn"
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [forj.lisa.plan-edn :as plan-edn]))

;; =============================================================================
;; check-gitignore tests
;; =============================================================================

(deftest check-gitignore-test
  (testing "Returns nil when not a git repo"
    (let [tmp (str (fs/create-temp-dir))]
      (is (nil? (plan-edn/check-gitignore tmp)))
      (fs/delete-tree tmp)))

  (testing "Returns missing entries for git repo without .gitignore"
    (let [tmp (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path tmp ".git"))
      (let [missing (plan-edn/check-gitignore tmp)]
        (is (seq missing))
        (is (some #{"LISA_PLAN.edn"} missing))
        (is (some #{".forj/"} missing)))
      (fs/delete-tree tmp)))

  (testing "Returns nil when .gitignore covers everything"
    (let [tmp (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path tmp ".git"))
      (spit (str (fs/path tmp ".gitignore")) "LISA_PLAN.edn\n.forj/\n")
      (is (nil? (plan-edn/check-gitignore tmp)))
      (fs/delete-tree tmp)))

  (testing "Returns only missing entries"
    (let [tmp (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path tmp ".git"))
      (spit (str (fs/path tmp ".gitignore")) "LISA_PLAN.edn\n")
      (let [missing (plan-edn/check-gitignore tmp)]
        (is (= 1 (count missing)))
        (is (some #{".forj/"} missing)))
      (fs/delete-tree tmp))))

;; =============================================================================
;; add-to-gitignore! tests
;; =============================================================================

(deftest add-to-gitignore!-test
  (testing "No-op when not a git repo"
    (let [tmp (str (fs/create-temp-dir))]
      (plan-edn/add-to-gitignore! tmp ["LISA_PLAN.edn"])
      (is (not (fs/exists? (fs/path tmp ".gitignore"))))
      (fs/delete-tree tmp)))

  (testing "Creates .gitignore with entries when none exists"
    (let [tmp (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path tmp ".git"))
      (plan-edn/add-to-gitignore! tmp ["LISA_PLAN.edn" ".forj/"])
      (let [content (slurp (str (fs/path tmp ".gitignore")))]
        (is (re-find #"LISA_PLAN.edn" content))
        (is (re-find #"\.forj/" content))
        (is (re-find #"# forj" content)))
      (fs/delete-tree tmp)))

  (testing "Appends to existing .gitignore"
    (let [tmp (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path tmp ".git"))
      (spit (str (fs/path tmp ".gitignore")) "node_modules/\n")
      (plan-edn/add-to-gitignore! tmp ["LISA_PLAN.edn"])
      (let [content (slurp (str (fs/path tmp ".gitignore")))]
        (is (re-find #"node_modules/" content) "Should preserve existing entries")
        (is (re-find #"LISA_PLAN.edn" content) "Should add new entry"))
      (fs/delete-tree tmp))))

;; =============================================================================
;; review-checkpoint? tests
;; =============================================================================

(deftest review-checkpoint?-test
  (testing "Returns true for checkpoint with :type :review"
    (is (true? (plan-edn/review-checkpoint? {:id :review-auth
                                             :type :review
                                             :description "Review auth"}))))

  (testing "Returns false for checkpoint without :type"
    (is (false? (plan-edn/review-checkpoint? {:id :build-auth
                                              :description "Build auth"}))))

  (testing "Returns false for checkpoint with non-review type"
    (is (false? (plan-edn/review-checkpoint? {:id :build-auth
                                              :type :normal
                                              :description "Build auth"}))))

  (testing "Returns false for nil checkpoint"
    (is (false? (plan-edn/review-checkpoint? nil)))))

;; =============================================================================
;; duration-ms tests
;; =============================================================================

(deftest duration-ms-test
  (testing "Returns correct ms between two timestamps"
    (is (= 5000 (plan-edn/duration-ms "2026-01-01T00:00:00Z" "2026-01-01T00:00:05Z"))))

  (testing "Returns correct ms for minutes"
    (is (= 120000 (plan-edn/duration-ms "2026-01-01T00:00:00Z" "2026-01-01T00:02:00Z"))))

  (testing "Returns correct ms for hours"
    (is (= 3600000 (plan-edn/duration-ms "2026-01-01T00:00:00Z" "2026-01-01T01:00:00Z"))))

  (testing "Returns 0 for identical timestamps"
    (is (= 0 (plan-edn/duration-ms "2026-01-01T00:00:00Z" "2026-01-01T00:00:00Z"))))

  (testing "Handles sub-second precision"
    (is (= 500 (plan-edn/duration-ms "2026-01-01T00:00:00Z" "2026-01-01T00:00:00.500Z"))))

  (testing "Handles nil gracefully - throws on nil start"
    (is (thrown? Exception (plan-edn/duration-ms nil "2026-01-01T00:00:00Z"))))

  (testing "Handles nil gracefully - throws on nil end"
    (is (thrown? Exception (plan-edn/duration-ms "2026-01-01T00:00:00Z" nil))))

  (testing "Handles nil gracefully - throws on both nil"
    (is (thrown? Exception (plan-edn/duration-ms nil nil)))))

;; =============================================================================
;; format-duration tests
;; =============================================================================

(deftest format-duration-test
  (testing "Produces 'Xs' for short durations (seconds only)"
    (is (= "0s" (plan-edn/format-duration 0)))
    (is (= "1s" (plan-edn/format-duration 1000)))
    (is (= "45s" (plan-edn/format-duration 45000)))
    (is (= "59s" (plan-edn/format-duration 59000))))

  (testing "Produces 'Xm Ys' for minute durations"
    (is (= "1m 0s" (plan-edn/format-duration 60000)))
    (is (= "2m 34s" (plan-edn/format-duration 154000)))
    (is (= "59m 59s" (plan-edn/format-duration 3599000))))

  (testing "Produces 'Xh Ym' for hour durations"
    (is (= "1h 0m" (plan-edn/format-duration 3600000)))
    (is (= "1h 5m" (plan-edn/format-duration 3900000)))
    (is (= "2h 30m" (plan-edn/format-duration 9000000))))

  (testing "Sub-second values truncate to 0s"
    (is (= "0s" (plan-edn/format-duration 999)))))

;; =============================================================================
;; mark-checkpoint-done! duration tracking tests
;; =============================================================================

(deftest mark-checkpoint-done!-duration-test
  (testing "Stores :duration-ms when :started is set"
    (let [tmp (str (fs/create-temp-dir))]
      (plan-edn/create-plan! tmp {:title "Duration test"
                                  :checkpoints [{:id :task-a
                                                 :description "Task A"}]})
      ;; The first checkpoint gets :started automatically from create-plan!
      (let [plan-before (plan-edn/read-plan tmp)
            cp-before (plan-edn/checkpoint-by-id plan-before :task-a)]
        (is (some? (:started cp-before)) "Should have :started from create-plan!"))

      ;; Small delay to ensure non-zero duration
      (Thread/sleep 10)
      (plan-edn/mark-checkpoint-done! tmp :task-a)

      (let [plan-after (plan-edn/read-plan tmp)
            cp-after (plan-edn/checkpoint-by-id plan-after :task-a)]
        (is (some? (:duration-ms cp-after)) "Should have :duration-ms")
        (is (pos? (:duration-ms cp-after)) "Duration should be positive")
        (is (some? (:completed cp-after)) "Should have :completed"))
      (fs/delete-tree tmp)))

  (testing "Does not store :duration-ms when :started is not set"
    (let [tmp (str (fs/create-temp-dir))]
      ;; Manually write a plan where checkpoint has no :started
      (plan-edn/write-plan! tmp {:title "No started test"
                                 :status :in-progress
                                 :checkpoints [{:id :task-no-start
                                                :status :in-progress
                                                :description "Task without started"}]
                                 :signs []})
      (plan-edn/mark-checkpoint-done! tmp :task-no-start)

      (let [plan-after (plan-edn/read-plan tmp)
            cp-after (plan-edn/checkpoint-by-id plan-after :task-no-start)]
        (is (nil? (:duration-ms cp-after)) "Should NOT have :duration-ms without :started")
        (is (some? (:completed cp-after)) "Should have :completed"))
      (fs/delete-tree tmp))))

;; =============================================================================
;; mark-checkpoint-failed! duration tracking tests
;; =============================================================================

(deftest mark-checkpoint-failed!-duration-test
  (testing "Stores :duration-ms when :started is set"
    (let [tmp (str (fs/create-temp-dir))]
      (plan-edn/create-plan! tmp {:title "Failed duration test"
                                  :checkpoints [{:id :task-fail
                                                 :description "Task that fails"}]})
      ;; The first checkpoint gets :started automatically
      (Thread/sleep 10)
      (plan-edn/mark-checkpoint-failed! tmp :task-fail "Something went wrong")

      (let [plan-after (plan-edn/read-plan tmp)
            cp-after (plan-edn/checkpoint-by-id plan-after :task-fail)]
        (is (some? (:duration-ms cp-after)) "Should have :duration-ms")
        (is (pos? (:duration-ms cp-after)) "Duration should be positive")
        (is (some? (:failed-at cp-after)) "Should have :failed-at")
        (is (= "Something went wrong" (:failure-reason cp-after))))
      (fs/delete-tree tmp)))

  (testing "Does not store :duration-ms when :started is not set"
    (let [tmp (str (fs/create-temp-dir))]
      (plan-edn/write-plan! tmp {:title "No started fail test"
                                 :status :in-progress
                                 :checkpoints [{:id :task-no-start-fail
                                                :status :in-progress
                                                :description "Task without started that fails"}]
                                 :signs []})
      (plan-edn/mark-checkpoint-failed! tmp :task-no-start-fail "No started")

      (let [plan-after (plan-edn/read-plan tmp)
            cp-after (plan-edn/checkpoint-by-id plan-after :task-no-start-fail)]
        (is (nil? (:duration-ms cp-after)) "Should NOT have :duration-ms without :started")
        (is (some? (:failed-at cp-after)) "Should have :failed-at"))
      (fs/delete-tree tmp))))

;; =============================================================================
;; Review checkpoint persistence tests
;; =============================================================================

(deftest review-checkpoint-persistence-test
  (testing "create-plan! persists :type :review on checkpoints"
    (let [tmp (str (fs/create-temp-dir))]
      (plan-edn/create-plan! tmp {:title "Test plan"
                                  :checkpoints [{:id :build
                                                 :description "Build it"}
                                                {:id :review-build
                                                 :type :review
                                                 :description "Review the build"
                                                 :depends-on [:build]}]})
      (let [plan (plan-edn/read-plan tmp)
            review-cp (plan-edn/checkpoint-by-id plan :review-build)
            normal-cp (plan-edn/checkpoint-by-id plan :build)]
        (is (= :review (:type review-cp)))
        (is (nil? (:type normal-cp)))
        (is (true? (plan-edn/review-checkpoint? review-cp)))
        (is (false? (plan-edn/review-checkpoint? normal-cp))))
      (fs/delete-tree tmp)))

  (testing "add-checkpoint! persists :type :review"
    (let [tmp (str (fs/create-temp-dir))]
      (plan-edn/create-plan! tmp {:title "Test plan"
                                  :checkpoints [{:id :build
                                                 :description "Build it"}]})
      (plan-edn/add-checkpoint! tmp {:id :review-build
                                     :type :review
                                     :description "Review the build"})
      (let [plan (plan-edn/read-plan tmp)
            review-cp (plan-edn/checkpoint-by-id plan :review-build)]
        (is (= :review (:type review-cp)))
        (is (true? (plan-edn/review-checkpoint? review-cp))))
      (fs/delete-tree tmp))))

(comment
  (clojure.test/run-tests 'forj.lisa.plan-edn-test))
