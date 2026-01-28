(ns forj.mcp.test-runner
  "Test runner for forj-mcp tests."
  (:require [clojure.test :as t]
            [forj.mcp.tools-test]
            [forj.lisa.analytics-test]
            [forj.lisa.claude-sessions-test]
            [forj.lisa.validation-test]
            [forj.scaffold-test]))

(defn run-tests []
  (let [result (t/run-tests 'forj.mcp.tools-test 'forj.lisa.analytics-test 'forj.lisa.claude-sessions-test 'forj.lisa.validation-test 'forj.scaffold-test)]
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
