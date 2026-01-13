(ns {{namespace}}.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [{{namespace}}.core :as core]))

(deftest greet-test
  (testing "greet returns proper greeting"
    (is (= "Hello, World!" (core/greet "World")))
    (is (= "Hello, Claude!" (core/greet "Claude")))))

(defn -main [& _args]
  (let [{:keys [fail error]} (clojure.test/run-tests '{{namespace}}.core-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
