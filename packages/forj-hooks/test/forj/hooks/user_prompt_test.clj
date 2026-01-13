(ns forj.hooks.user-prompt-test
  "Tests for forj.hooks.user-prompt"
  (:require [clojure.test :refer [deftest testing is]]
            [forj.hooks.user-prompt :as user-prompt]
            [cheshire.core :as json]))

;; =============================================================================
;; is-clojure-project? tests
;; =============================================================================

(deftest is-clojure-project?-test
  (testing "Detects Clojure project"
    (is (user-prompt/is-clojure-project? "."))
    (is (not (user-prompt/is-clojure-project? "/tmp")))))

;; =============================================================================
;; Hook output format tests
;; =============================================================================

(deftest hook-output-format-test
  (testing "Hook produces valid JSON with correct structure"
    (let [output {:hookSpecificOutput
                  {:hookEventName "UserPromptSubmit"
                   :additionalContext "Reminder: This is a Clojure project."}}
          json-str (json/generate-string output)
          parsed (json/parse-string json-str true)]
      (is (= "UserPromptSubmit" (get-in parsed [:hookSpecificOutput :hookEventName])))
      (is (string? (get-in parsed [:hookSpecificOutput :additionalContext]))))))
