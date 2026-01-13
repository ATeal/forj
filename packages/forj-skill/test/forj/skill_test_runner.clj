(ns forj.skill-test-runner
  "Test runner for skill validation tests."
  (:require [clojure.test :as t]
            [forj.skill-test]))

(defn run-tests []
  (let [result (t/run-tests 'forj.skill-test)]
    (if (and (zero? (:fail result))
             (zero? (:error result)))
      (do
        (println "\n✓ All tests passed!")
        (System/exit 0))
      (do
        (println "\n✗ Some tests failed")
        (System/exit 1)))))

(defn -main [& _args]
  (run-tests))
