(ns forj.lisa.validation-test
  "Tests for forj.lisa.validation"
  (:require [clojure.test :refer [deftest testing is]]
            [forj.lisa.validation :as validation]))

;; =============================================================================
;; parse-validation-string tests
;; =============================================================================

(deftest parse-validation-string-test
  (testing "Parses REPL validations"
    (let [result (validation/parse-validation-string "repl:(+ 1 2) => 3")]
      (is (= 1 (count result)))
      (is (= :repl (:type (first result))))
      (is (= "(+ 1 2) => 3" (:expression (first result))))))

  (testing "Parses Chrome validations"
    (let [result (validation/parse-validation-string "chrome:screenshot /tmp/test.png")]
      (is (= 1 (count result)))
      (is (= :chrome (:type (first result))))
      (is (= :screenshot (:action (first result))))
      (is (= "/tmp/test.png" (:args (first result))))))

  (testing "Parses Judge validations"
    (let [result (validation/parse-validation-string "judge:UI looks clean")]
      (is (= 1 (count result)))
      (is (= :judge (:type (first result))))
      (is (= "UI looks clean" (:criteria (first result))))))

  (testing "Parses multiple validations separated by |"
    (let [result (validation/parse-validation-string "repl:(+ 1 2) => 3 | judge:Looks good")]
      (is (= 2 (count result)))
      (is (= :repl (:type (first result))))
      (is (= :judge (:type (second result))))))

  (testing "Parses validations separated by newlines"
    (let [result (validation/parse-validation-string "repl:(foo)\njudge:Nice")]
      (is (= 2 (count result)))
      (is (= :repl (:type (first result))))
      (is (= :judge (:type (second result))))))

  (testing "Defaults to REPL type when no prefix"
    (let [result (validation/parse-validation-string "(+ 1 2)")]
      (is (= 1 (count result)))
      (is (= :repl (:type (first result))))
      (is (= "(+ 1 2)" (:expression (first result))))))

  (testing "Returns nil for blank or nil input"
    (is (nil? (validation/parse-validation-string nil)))
    (is (nil? (validation/parse-validation-string "")))
    (is (nil? (validation/parse-validation-string "   "))))

  (testing "Parses Chrome navigate action"
    (let [result (validation/parse-validation-string "chrome:navigate http://localhost:3000")]
      (is (= :chrome (:type (first result))))
      (is (= :navigate (:action (first result))))
      (is (= "http://localhost:3000" (:args (first result))))))

  (testing "Parses Chrome snapshot action"
    (let [result (validation/parse-validation-string "chrome:snapshot")]
      (is (= :chrome (:type (first result))))
      (is (= :snapshot (:action (first result))))
      (is (nil? (:args (first result)))))))

;; =============================================================================
;; run-validation tests (REPL type - requires running nREPL)
;; =============================================================================

(deftest run-validation-repl-test
  (testing "Runs REPL validation with expected result"
    (let [validation-item {:type :repl :expression "(+ 1 2) => 3" :raw "repl:(+ 1 2) => 3"}
          result (validation/run-validation validation-item {:port 1669})]
      (is (= :repl (:type result)))
      (is (true? (:passed result)))
      (is (= "3" (:actual result)))
      (is (= "3" (:expected result)))))

  (testing "Fails REPL validation with wrong expected result"
    (let [validation-item {:type :repl :expression "(+ 1 2) => 5" :raw "repl:(+ 1 2) => 5"}
          result (validation/run-validation validation-item {:port 1669})]
      (is (= :repl (:type result)))
      (is (false? (:passed result)))
      (is (= "3" (:actual result)))
      (is (= "5" (:expected result)))))

  (testing "Passes REPL validation without expected result"
    (let [validation-item {:type :repl :expression "(+ 1 2)" :raw "repl:(+ 1 2)"}
          result (validation/run-validation validation-item {:port 1669})]
      (is (= :repl (:type result)))
      (is (true? (:passed result)))
      (is (= "3" (:actual result)))
      (is (nil? (:expected result)))))

  (testing "Reports error for invalid expression"
    ;; When expecting a specific value, an error won't match
    (let [validation-item {:type :repl :expression "(undefined-fn) => 42" :raw "repl:(undefined-fn) => 42"}
          result (validation/run-validation validation-item {:port 1669})]
      (is (= :repl (:type result)))
      (is (false? (:passed result))))))

;; =============================================================================
;; run-validation tests (unknown type)
;; =============================================================================

(deftest run-validation-unknown-type-test
  (testing "Returns error for unknown validation type"
    (let [validation-item {:type :unknown :raw "unknown:something"}
          result (validation/run-validation validation-item {})]
      (is (false? (:passed result)))
      (is (re-find #"Unknown validation type" (:error result))))))

;; =============================================================================
;; run-validations tests (multiple validations)
;; =============================================================================

(deftest run-validations-test
  (testing "Runs multiple REPL validations"
    (let [result (validation/run-validations
                  "repl:(+ 1 2) => 3 | repl:(* 2 3) => 6"
                  {:port 1669})]
      (is (true? (:all-passed result)))
      (is (= 2 (:passed-count result)))
      (is (= 0 (:failed-count result)))
      (is (re-find #"PASSED" (:summary result)))))

  (testing "Reports mixed results correctly"
    (let [result (validation/run-validations
                  "repl:(+ 1 2) => 3 | repl:(+ 1 2) => 99"
                  {:port 1669})]
      (is (false? (:all-passed result)))
      (is (= 1 (:passed-count result)))
      (is (= 1 (:failed-count result)))
      (is (re-find #"FAILED" (:summary result)))))

  (testing "Returns summary for empty validations"
    (let [result (validation/run-validations "" {:port 1669})]
      (is (= "No validations defined" (:summary result)))))

  (testing "Returns summary for nil validations"
    (let [result (validation/run-validations nil {:port 1669})]
      (is (= "No validations defined" (:summary result))))))

;; =============================================================================
;; checkpoint-gates-passed? tests
;; =============================================================================

(deftest checkpoint-gates-passed?-test
  (testing "Passes when no gates defined"
    (let [result (validation/checkpoint-gates-passed? nil {})]
      (is (true? (:passed result)))
      (is (= "No gates defined" (:message result)))))

  (testing "Passes when gates string is blank"
    (let [result (validation/checkpoint-gates-passed? "   " {})]
      (is (true? (:passed result)))))

  (testing "Passes when all gates pass"
    (let [result (validation/checkpoint-gates-passed?
                  "repl:(+ 1 2) => 3"
                  {:port 1669})]
      (is (true? (:passed result)))
      (is (re-find #"PASSED" (:message result)))))

  (testing "Fails when any gate fails"
    (let [result (validation/checkpoint-gates-passed?
                  "repl:(+ 1 2) => 99"
                  {:port 1669})]
      (is (false? (:passed result)))
      (is (re-find #"FAILED" (:message result))))))

(comment
  ;; Run tests from REPL
  (clojure.test/run-tests 'forj.lisa.validation-test)
  )
