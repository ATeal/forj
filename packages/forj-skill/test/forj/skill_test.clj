(ns forj.skill-test
  "Tests for skill definition validation"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(def skill-path "packages/forj-skill/SKILL.md")
(def lisa-loop-path "packages/forj-skill/lisa-loop/SKILL.md")
(def lisa-agent-teams-path "packages/forj-skill/lisa-agent-teams/SKILL.md")

;; =============================================================================
;; Helper for validating any SKILL.md frontmatter
;; =============================================================================

(defn- validate-frontmatter
  "Validate that a SKILL.md has valid YAML frontmatter with name and description."
  [path]
  (let [content (slurp path)
        frontmatter-match (re-find #"(?s)^---\n(.+?)\n---" content)]
    (is frontmatter-match (str path " should have YAML frontmatter"))
    (when frontmatter-match
      (let [frontmatter (second frontmatter-match)]
        (is (re-find #"name:\s*\S+" frontmatter) (str path " should have 'name' field"))
        (is (re-find #"description:\s*.+" frontmatter) (str path " should have 'description' field"))))))

;; =============================================================================
;; SKILL.md validation tests (clj-repl)
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

;; =============================================================================
;; lisa-loop SKILL.md tests
;; =============================================================================

(deftest lisa-loop-skill-exists-test
  (testing "lisa-loop SKILL.md exists"
    (is (fs/exists? lisa-loop-path) "lisa-loop/SKILL.md should exist")))

(deftest lisa-loop-frontmatter-test
  (testing "lisa-loop has valid frontmatter"
    (validate-frontmatter lisa-loop-path)))

(deftest lisa-loop-content-test
  (testing "lisa-loop documents key tools"
    (let [content (slurp lisa-loop-path)]
      (is (re-find #"lisa_create_plan" content) "Should mention lisa_create_plan")
      (is (re-find #"lisa_run_orchestrator" content) "Should mention lisa_run_orchestrator")
      (is (re-find #"LISA_PLAN.edn" content) "Should mention LISA_PLAN.edn")))

  (testing "lisa-loop does NOT contain agent teams content"
    (let [content (slurp lisa-loop-path)]
      (is (not (re-find #"--agent-teams" content)) "Should not mention --agent-teams flag")
      (is (not (re-find #"Agent Teams Mode" content)) "Should not have Agent Teams section")
      (is (not (re-find #"TeamCreate" content)) "Should not mention TeamCreate")
      (is (not (re-find #"lisa_plan_to_tasks" content)) "Should not mention lisa_plan_to_tasks"))))

;; =============================================================================
;; lisa-agent-teams SKILL.md tests
;; =============================================================================

(deftest lisa-agent-teams-skill-exists-test
  (testing "lisa-agent-teams SKILL.md exists"
    (is (fs/exists? lisa-agent-teams-path) "lisa-agent-teams/SKILL.md should exist")))

(deftest lisa-agent-teams-frontmatter-test
  (testing "lisa-agent-teams has valid frontmatter"
    (validate-frontmatter lisa-agent-teams-path)))

(deftest lisa-agent-teams-content-test
  (testing "lisa-agent-teams documents key tools and concepts"
    (let [content (slurp lisa-agent-teams-path)]
      (is (re-find #"lisa_plan_to_tasks" content) "Should mention lisa_plan_to_tasks")
      (is (re-find #"TeamCreate" content) "Should mention TeamCreate")
      (is (re-find #"TaskCompleted" content) "Should mention TaskCompleted hook")
      (is (re-find #"TeammateIdle" content) "Should mention TeammateIdle hook")
      (is (re-find #"FORJ_AGENT_TEAMS" content) "Should mention FORJ_AGENT_TEAMS env var")
      (is (re-find #"(?i)experimental" content) "Should be labeled experimental")))

  (testing "lisa-agent-teams does NOT mention orchestrator execution"
    (let [content (slurp lisa-agent-teams-path)]
      (is (not (re-find #"lisa_run_orchestrator" content)) "Should not mention lisa_run_orchestrator"))))
