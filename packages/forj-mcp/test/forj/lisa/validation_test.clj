(ns forj.lisa.validation-test
  "Tests for forj.lisa.validation"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [babashka.process :as p]
            [forj.lisa.validation :as validation]))

;; =============================================================================
;; Test fixture: start a bb nREPL for REPL validation tests
;; =============================================================================

(def ^:dynamic *nrepl-port* nil)

(defn- wait-for-nrepl
  "Poll until nREPL is accepting connections on port, or timeout."
  [port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        false
        (let [result (p/shell {:out :string :err :string :continue true}
                              "clj-nrepl-eval" "-p" (str port) "-t" "2000" "(+ 1 1)")]
          (if (zero? (:exit result))
            true
            (do (Thread/sleep 200)
                (recur))))))))

(defn- find-free-port []
  (let [ss (java.net.ServerSocket. 0)
        port (.getLocalPort ss)]
    (.close ss)
    port))

(defn nrepl-fixture [f]
  (let [port (find-free-port)
        proc (p/process {:out :inherit :err :inherit}
                        "bb" "--nrepl-server" (str port))]
    (try
      (if (wait-for-nrepl port 10000)
        (binding [*nrepl-port* port]
          (f))
        (do
          (println "WARN: Could not start nREPL for validation tests, skipping REPL tests")
          (f)))
      (finally
        (p/destroy-tree proc)))))

(use-fixtures :once nrepl-fixture)

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
  (when-not *nrepl-port*
    (println "SKIP: run-validation-repl-test (no nREPL available)"))
  (when *nrepl-port*
    (testing "Runs REPL validation with expected result"
      (let [validation-item {:type :repl :expression "(+ 1 2) => 3" :raw "repl:(+ 1 2) => 3"}
            result (validation/run-validation validation-item {:port *nrepl-port*})]
        (is (= :repl (:type result)))
        (is (true? (:passed result)))
        (is (= "3" (:actual result)))
        (is (= "3" (:expected result)))))

    (testing "Fails REPL validation with wrong expected result"
      (let [validation-item {:type :repl :expression "(+ 1 2) => 5" :raw "repl:(+ 1 2) => 5"}
            result (validation/run-validation validation-item {:port *nrepl-port*})]
        (is (= :repl (:type result)))
        (is (false? (:passed result)))
        (is (= "3" (:actual result)))
        (is (= "5" (:expected result)))))

    (testing "Passes REPL validation without expected result"
      (let [validation-item {:type :repl :expression "(+ 1 2)" :raw "repl:(+ 1 2)"}
            result (validation/run-validation validation-item {:port *nrepl-port*})]
        (is (= :repl (:type result)))
        (is (true? (:passed result)))
        (is (= "3" (:actual result)))
        (is (nil? (:expected result)))))

    (testing "Reports error for invalid expression"
      (let [validation-item {:type :repl :expression "(undefined-fn) => 42" :raw "repl:(undefined-fn) => 42"}
            result (validation/run-validation validation-item {:port *nrepl-port*})]
        (is (= :repl (:type result)))
        (is (false? (:passed result)))))))

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
  (when-not *nrepl-port*
    (println "SKIP: run-validations-test (no nREPL available)"))
  (when *nrepl-port*
    (testing "Runs multiple REPL validations"
      (let [result (validation/run-validations
                    "repl:(+ 1 2) => 3 | repl:(* 2 3) => 6"
                    {:port *nrepl-port*})]
        (is (true? (:all-passed result)))
        (is (= 2 (:passed-count result)))
        (is (= 0 (:failed-count result)))
        (is (re-find #"PASSED" (:summary result)))))

    (testing "Reports mixed results correctly"
      (let [result (validation/run-validations
                    "repl:(+ 1 2) => 3 | repl:(+ 1 2) => 99"
                    {:port *nrepl-port*})]
        (is (false? (:all-passed result)))
        (is (= 1 (:passed-count result)))
        (is (= 1 (:failed-count result)))
        (is (re-find #"FAILED" (:summary result))))))

  (testing "Returns summary for empty validations"
    (let [result (validation/run-validations "" {:port 99999})]
      (is (= "No validations defined" (:summary result)))))

  (testing "Returns summary for nil validations"
    (let [result (validation/run-validations nil {:port 99999})]
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

  (when *nrepl-port*
    (testing "Passes when all gates pass"
      (let [result (validation/checkpoint-gates-passed?
                    "repl:(+ 1 2) => 3"
                    {:port *nrepl-port*})]
        (is (true? (:passed result)))
        (is (re-find #"PASSED" (:message result)))))

    (testing "Fails when any gate fails"
      (let [result (validation/checkpoint-gates-passed?
                    "repl:(+ 1 2) => 99"
                    {:port *nrepl-port*})]
        (is (false? (:passed result)))
        (is (re-find #"FAILED" (:message result)))))))

(comment
  ;; Run tests from REPL
  (clojure.test/run-tests 'forj.lisa.validation-test))
