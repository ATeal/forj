(ns forj.skill-test
  "Tests for skill definition validation"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(def skill-path "packages/forj-skill/SKILL.md")

;; =============================================================================
;; SKILL.md validation tests
;; =============================================================================

(deftest skill-file-exists-test
  (testing "SKILL.md file exists"
    (is (fs/exists? skill-path) "SKILL.md should exist")))

(deftest skill-frontmatter-test
  (testing "SKILL.md has valid YAML frontmatter"
    (let [content (slurp skill-path)
          ;; Extract frontmatter between --- markers
          frontmatter-match (re-find #"(?s)^---\n(.+?)\n---" content)]
      (is frontmatter-match "Should have YAML frontmatter")
      (when frontmatter-match
        (let [frontmatter (second frontmatter-match)]
          (is (re-find #"name:\s*\S+" frontmatter) "Should have 'name' field")
          (is (re-find #"description:\s*.+" frontmatter) "Should have 'description' field"))))))

(deftest skill-required-sections-test
  (testing "SKILL.md has required sections"
    (let [content (slurp skill-path)]
      (is (re-find #"(?i)## Quick Reference" content) "Should have Quick Reference section")
      (is (re-find #"(?i)## Instructions" content) "Should have Instructions section")
      (is (re-find #"(?i)## Troubleshooting" content) "Should have Troubleshooting section"))))

(deftest skill-commands-documented-test
  (testing "SKILL.md documents expected commands"
    (let [content (slurp skill-path)]
      (is (re-find #"/clj-repl" content) "Should document /clj-repl command")
      (is (re-find #"bb" content) "Should mention Babashka")
      (is (re-find #"clj|clojure" content) "Should mention Clojure"))))

(deftest skill-repl-types-test
  (testing "SKILL.md covers all REPL types"
    (let [content (str/lower-case (slurp skill-path))]
      (is (re-find #"babashka" content) "Should cover Babashka")
      (is (re-find #"clojure" content) "Should cover Clojure")
      (is (re-find #"shadow-cljs|clojurescript" content) "Should cover ClojureScript"))))
