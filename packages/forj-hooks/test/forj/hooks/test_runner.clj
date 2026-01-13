(ns forj.hooks.test-runner
  "Test runner for forj-hooks tests."
  (:require [clojure.test :as t]
            [forj.hooks.session-start-test]
            [forj.hooks.user-prompt-test]))

(defn run-tests []
  (let [result (t/run-tests 'forj.hooks.session-start-test
                            'forj.hooks.user-prompt-test)]
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
