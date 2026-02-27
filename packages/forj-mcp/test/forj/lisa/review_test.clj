(ns forj.lisa.review-test
  "Tests for forj.lisa.review"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [forj.lisa.review :as review]))

;; =============================================================================
;; Test helpers
;; =============================================================================

(defn- create-test-project
  "Create a temporary project directory with a plan and optional meta files.
   Returns the project path."
  [{:keys [plan meta-files]}]
  (let [tmp-dir (str (fs/create-temp-dir {:prefix "review-test-"}))
        plan-path (str (fs/path tmp-dir "LISA_PLAN.edn"))
        log-dir (str (fs/path tmp-dir ".forj/logs/lisa"))]
    (fs/create-dirs log-dir)
    (spit plan-path (pr-str plan))
    (doseq [{:keys [filename content]} meta-files]
      (spit (str (fs/path log-dir filename))
            (json/generate-string content {:pretty true})))
    tmp-dir))

(defn- cleanup-test-project [path]
  (fs/delete-tree path))

(def sample-plan
  {:title "Add JWT authentication"
   :status :in-progress
   :checkpoints
   [{:id :password-hashing
     :status :done
     :description "Create password hashing module"
     :completed "2024-01-19T10:00:00Z"}
    {:id :jwt-tokens
     :status :done
     :description "Create JWT token module"
     :depends-on [:password-hashing]
     :completed "2024-01-19T10:05:00Z"}
    {:id :review-auth
     :type :review
     :status :done
     :depends-on [:jwt-tokens]
     :description "Review auth implementation"
     :completed "2024-01-19T10:10:00Z"}]
   :signs
   [{:iteration 2
     :checkpoint :jwt-tokens
     :issue "Wrong namespace — use buddy.sign.jwt"
     :fix "Import buddy.sign.jwt not buddy.core.sign"
     :severity :error}
    {:iteration 5
     :issue "Timeout on :jwt-tokens"
     :fix "Consider smaller scope"
     :severity :warning}]})

(def sample-meta-files
  [{:filename "parallel-password-hashing-1-meta.json"
    :content {:session-id "sess-001"
              :checkpoint-id "password-hashing"
              :iteration 1
              :started-at "2024-01-19T10:00:00Z"
              :completed-at "2024-01-19T10:00:42Z"
              :success true
              :error nil}}
   {:filename "parallel-jwt-tokens-2-meta.json"
    :content {:session-id "sess-002"
              :checkpoint-id "jwt-tokens"
              :iteration 2
              :started-at "2024-01-19T10:01:00Z"
              :completed-at "2024-01-19T10:03:10Z"
              :success true
              :error nil}}
   {:filename "parallel-review-auth-3-meta.json"
    :content {:session-id "sess-003"
              :checkpoint-id "review-auth"
              :iteration 3
              :started-at "2024-01-19T10:04:00Z"
              :completed-at "2024-01-19T10:05:30Z"
              :success true
              :error nil}}])

;; =============================================================================
;; gather-review-data tests
;; =============================================================================

(deftest gather-review-data-with-full-project-test
  (let [project-path (create-test-project {:plan sample-plan
                                           :meta-files sample-meta-files})
        loop-result {:status :complete
                     :iterations 5
                     :total-cost 0.47
                     :total-input-tokens 4500000
                     :total-output-tokens 20000}]
    (try
      (let [result (review/gather-review-data project-path loop-result)]
        (testing "plan section"
          (is (= "Add JWT authentication" (get-in result [:plan :title])))
          (is (= :complete (get-in result [:plan :status])))
          (is (= 3 (get-in result [:plan :checkpoint-count]))))

        (testing "summary section"
          (is (= 5 (get-in result [:summary :total-iterations])))
          (is (= 0.47 (get-in result [:summary :total-cost])))
          (is (= 4500000 (get-in result [:summary :total-input-tokens])))
          (is (= 20000 (get-in result [:summary :total-output-tokens])))
          ;; Duration from meta files
          (is (some? (get-in result [:summary :duration-ms]))))

        (testing "checkpoints section"
          (is (= 3 (count (:checkpoints result))))
          (let [cp1 (first (:checkpoints result))]
            (is (= :password-hashing (:id cp1)))
            (is (= :done (:status cp1)))
            ;; Should have meta-file timing
            (is (= "2024-01-19T10:00:00Z" (:started cp1)))
            (is (= "2024-01-19T10:00:42Z" (:completed cp1)))
            (is (= 42000 (:duration-ms cp1)))
            (is (= 1 (:iterations cp1))))
          ;; Review checkpoint has :type
          (let [cp3 (nth (:checkpoints result) 2)]
            (is (= :review (:type cp3)))))

        (testing "signs section"
          (is (= 2 (count (:signs result))))
          (is (= "Wrong namespace — use buddy.sign.jwt" (:issue (first (:signs result))))))

        (testing "git section"
          ;; May or may not have commits (test project isn't a git repo)
          (is (map? (:git result))))

        (testing "repl compliance section"
          (is (map? (:repl-compliance result)))
          (is (some? (get-in result [:repl-compliance :overall])))))
      (finally
        (cleanup-test-project project-path)))))

(deftest gather-review-data-no-meta-files-test
  (let [project-path (create-test-project {:plan sample-plan :meta-files []})
        loop-result {:status :complete :iterations 3 :total-cost 0.10
                     :total-input-tokens 100000 :total-output-tokens 5000}]
    (try
      (let [result (review/gather-review-data project-path loop-result)]
        (testing "gracefully handles missing meta files"
          (is (= 3 (get-in result [:plan :checkpoint-count])))
          (is (= 3 (get-in result [:summary :total-iterations])))
          ;; Checkpoints should still be present from plan
          (is (= 3 (count (:checkpoints result))))
          ;; No duration from meta files
          (is (nil? (get-in result [:summary :duration-ms])))
          ;; Per-checkpoint timing should be nil
          (is (nil? (:duration-ms (first (:checkpoints result)))))))
      (finally
        (cleanup-test-project project-path)))))

(deftest gather-review-data-no-signs-test
  (let [plan (dissoc sample-plan :signs)
        project-path (create-test-project {:plan plan :meta-files sample-meta-files})
        loop-result {:status :complete :iterations 3 :total-cost 0.20
                     :total-input-tokens 200000 :total-output-tokens 10000}]
    (try
      (let [result (review/gather-review-data project-path loop-result)]
        (testing "handles plan with no signs"
          (is (empty? (:signs result)))
          ;; Checkpoints should have empty signs
          (is (every? #(empty? (:signs %)) (:checkpoints result)))))
      (finally
        (cleanup-test-project project-path)))))

(deftest gather-review-data-incomplete-plan-test
  (let [plan (assoc-in sample-plan [:checkpoints 2 :status] :pending)
        project-path (create-test-project {:plan plan :meta-files []})
        loop-result {:status :max-iterations :iterations 20 :total-cost 1.50
                     :total-input-tokens 500000 :total-output-tokens 30000}]
    (try
      (let [result (review/gather-review-data project-path loop-result)]
        (testing "handles incomplete plan"
          (is (= :in-progress (get-in result [:plan :status])))
          (is (= 20 (get-in result [:summary :total-iterations])))))
      (finally
        (cleanup-test-project project-path)))))

;; =============================================================================
;; format-review tests
;; =============================================================================

(deftest format-review-produces-expected-sections-test
  (let [project-path (create-test-project {:plan sample-plan
                                           :meta-files sample-meta-files})
        loop-result {:status :complete :iterations 5 :total-cost 0.47
                     :total-input-tokens 4500000 :total-output-tokens 20000}]
    (try
      (let [review-data (review/gather-review-data project-path loop-result)
            formatted (review/format-review review-data)]
        (testing "contains header"
          (is (str/includes? formatted "Lisa Loop Review"))
          (is (str/includes? formatted "Add JWT authentication"))
          (is (str/includes? formatted "$0.47")))

        (testing "contains checkpoints"
          (is (str/includes? formatted "Per-Checkpoint Summary"))
          (is (str/includes? formatted "password-hashing"))
          (is (str/includes? formatted "jwt-tokens"))
          (is (str/includes? formatted "[REVIEW]")))

        (testing "contains signs"
          (is (str/includes? formatted "Signs: 2 total"))
          (is (str/includes? formatted "Wrong namespace")))

        (testing "is a non-empty string"
          (is (string? formatted))
          (is (pos? (count formatted)))))
      (finally
        (cleanup-test-project project-path)))))

(deftest format-review-minimal-data-test
  (let [review-data {:plan {:title "Simple task" :status :complete :checkpoint-count 1}
                     :summary {:total-iterations 1 :total-cost 0.05
                               :total-input-tokens 50000 :total-output-tokens 2000
                               :duration-ms nil}
                     :checkpoints [{:id :only-task :status :done :type nil
                                    :iterations 1 :duration-ms 30000
                                    :signs [] :files-changed [] :commit-message nil
                                    :commit-sha nil}]
                     :signs []
                     :git {:commits []}
                     :repl-compliance {:overall :unknown :per-checkpoint {}}}
        formatted (review/format-review review-data)]
    (testing "handles minimal data without errors"
      (is (string? formatted))
      (is (str/includes? formatted "Simple task"))
      (is (str/includes? formatted "only-task"))
      ;; Should NOT contain signs section since there are none
      (is (not (str/includes? formatted "Signs:"))))))
