(ns forj.lisa.plan-edn-test
  "Tests for forj.lisa.plan-edn gitignore management"
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [forj.lisa.plan-edn :as plan-edn]))

;; =============================================================================
;; check-gitignore tests
;; =============================================================================

(deftest check-gitignore-test
  (testing "Returns nil when not a git repo"
    (let [tmp (str (fs/create-temp-dir))]
      (is (nil? (plan-edn/check-gitignore tmp)))
      (fs/delete-tree tmp)))

  (testing "Returns missing entries for git repo without .gitignore"
    (let [tmp (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path tmp ".git"))
      (let [missing (plan-edn/check-gitignore tmp)]
        (is (seq missing))
        (is (some #{"LISA_PLAN.edn"} missing))
        (is (some #{".forj/"} missing)))
      (fs/delete-tree tmp)))

  (testing "Returns nil when .gitignore covers everything"
    (let [tmp (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path tmp ".git"))
      (spit (str (fs/path tmp ".gitignore")) "LISA_PLAN.edn\n.forj/\n")
      (is (nil? (plan-edn/check-gitignore tmp)))
      (fs/delete-tree tmp)))

  (testing "Returns only missing entries"
    (let [tmp (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path tmp ".git"))
      (spit (str (fs/path tmp ".gitignore")) "LISA_PLAN.edn\n")
      (let [missing (plan-edn/check-gitignore tmp)]
        (is (= 1 (count missing)))
        (is (some #{".forj/"} missing)))
      (fs/delete-tree tmp))))

;; =============================================================================
;; add-to-gitignore! tests
;; =============================================================================

(deftest add-to-gitignore!-test
  (testing "No-op when not a git repo"
    (let [tmp (str (fs/create-temp-dir))]
      (plan-edn/add-to-gitignore! tmp ["LISA_PLAN.edn"])
      (is (not (fs/exists? (fs/path tmp ".gitignore"))))
      (fs/delete-tree tmp)))

  (testing "Creates .gitignore with entries when none exists"
    (let [tmp (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path tmp ".git"))
      (plan-edn/add-to-gitignore! tmp ["LISA_PLAN.edn" ".forj/"])
      (let [content (slurp (str (fs/path tmp ".gitignore")))]
        (is (re-find #"LISA_PLAN.edn" content))
        (is (re-find #"\.forj/" content))
        (is (re-find #"# forj" content)))
      (fs/delete-tree tmp)))

  (testing "Appends to existing .gitignore"
    (let [tmp (str (fs/create-temp-dir))]
      (fs/create-dirs (fs/path tmp ".git"))
      (spit (str (fs/path tmp ".gitignore")) "node_modules/\n")
      (plan-edn/add-to-gitignore! tmp ["LISA_PLAN.edn"])
      (let [content (slurp (str (fs/path tmp ".gitignore")))]
        (is (re-find #"node_modules/" content) "Should preserve existing entries")
        (is (re-find #"LISA_PLAN.edn" content) "Should add new entry"))
      (fs/delete-tree tmp))))

(comment
  (clojure.test/run-tests 'forj.lisa.plan-edn-test))
