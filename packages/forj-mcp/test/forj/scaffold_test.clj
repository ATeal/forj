(ns forj.scaffold-test
  "Tests for forj.scaffold"
  (:require [clojure.test :refer [deftest testing is]]
            [forj.scaffold :as scaffold]
            [babashka.fs :as fs]))

;; =============================================================================
;; substitute-placeholders tests
;; =============================================================================

(deftest substitute-placeholders-test
  (testing "Replaces project-name placeholder"
    (is (= "my-app"
           (scaffold/substitute-placeholders "{{project-name}}" "my-app" {}))))

  (testing "Replaces namespace placeholder with underscores"
    (is (= "my_app"
           (scaffold/substitute-placeholders "{{namespace}}" "my-app" {})))
    (is (= "my_cool_app"
           (scaffold/substitute-placeholders "{{namespace}}" "my-cool-app" {}))))

  (testing "Replaces NPM version placeholders"
    (let [versions {:npm {"react" "18.2.0" "react-native" "0.72.0"}}]
      (is (= "\"react\": \"18.2.0\""
             (scaffold/substitute-placeholders "\"react\": \"{{npm:react}}\"" "app" versions)))
      (is (= "\"react-native\": \"0.72.0\""
             (scaffold/substitute-placeholders "\"react-native\": \"{{npm:react-native}}\"" "app" versions)))))

  (testing "Replaces Clojure version placeholders"
    (let [versions {:clj {"ring/ring-core" "1.10.0" "metosin/reitit" "0.7.0"}}]
      (is (= "ring/ring-core {:mvn/version \"1.10.0\"}"
             (scaffold/substitute-placeholders
              "ring/ring-core {:mvn/version \"{{clj:ring/ring-core}}\"}"
              "app" versions)))))

  (testing "Replaces ClojureScript version placeholders"
    (let [versions {:cljs {"reagent/reagent" "1.2.0"}}]
      (is (= "[reagent/reagent \"1.2.0\"]"
             (scaffold/substitute-placeholders
              "[reagent/reagent \"{{cljs:reagent/reagent}}\"]"
              "app" versions)))))

  (testing "Falls back to 0.0.0 for unknown versions"
    (is (= "\"unknown-pkg\": \"0.0.0\""
           (scaffold/substitute-placeholders "\"unknown-pkg\": \"{{npm:unknown-pkg}}\"" "app" {}))))

  (testing "Replaces multiple placeholders in one string"
    (let [versions {:npm {"react" "18.2.0"}}]
      (is (= "(ns my_app.core)\n\"react\": \"18.2.0\""
             (scaffold/substitute-placeholders
              "(ns {{namespace}}.core)\n\"react\": \"{{npm:react}}\""
              "my-app" versions))))))

;; =============================================================================
;; merge-deps-edn tests (private fn, test via #')
;; =============================================================================

(deftest merge-deps-edn-test
  (testing "Merges paths without duplicates"
    (let [result (#'scaffold/merge-deps-edn
                  "{:paths [\"src\"]}"
                  "{:paths [\"src\" \"resources\"]}")]
      (is (= ["src" "resources"] (:paths result)))))

  (testing "Merges deps maps"
    (let [result (#'scaffold/merge-deps-edn
                  "{:deps {org.clojure/clojure {:mvn/version \"1.11.1\"}}}"
                  "{:deps {ring/ring-core {:mvn/version \"1.10.0\"}}}")]
      (is (= 2 (count (:deps result))))
      (is (contains? (:deps result) 'org.clojure/clojure))
      (is (contains? (:deps result) 'ring/ring-core))))

  (testing "Deep merges aliases"
    (let [result (#'scaffold/merge-deps-edn
                  "{:aliases {:dev {:extra-paths [\"dev\"]}}}"
                  "{:aliases {:dev {:extra-deps {nrepl/nrepl {:mvn/version \"1.0.0\"}}}}}")]
      (is (= ["dev"] (get-in result [:aliases :dev :extra-paths])))
      (is (contains? (get-in result [:aliases :dev :extra-deps]) 'nrepl/nrepl))))

  (testing "Preserves cljd/opts if present"
    (let [result (#'scaffold/merge-deps-edn
                  "{:paths [\"src\"]}"
                  "{:cljd/opts {:kind :flutter}}")]
      (is (= {:kind :flutter} (:cljd/opts result))))))

;; =============================================================================
;; merge-bb-edn tests
;; =============================================================================

(deftest merge-bb-edn-test
  (testing "Merges paths"
    (let [result (#'scaffold/merge-bb-edn
                  "{:paths [\"src\"]}"
                  "{:paths [\"bb\"]}")]
      (is (= ["src" "bb"] (:paths result)))))

  (testing "Merges tasks"
    (let [result (#'scaffold/merge-bb-edn
                  "{:tasks {dev (println \"dev\")}}"
                  "{:tasks {test (println \"test\")}}")]
      (is (contains? (:tasks result) 'dev))
      (is (contains? (:tasks result) 'test)))))

;; =============================================================================
;; merge-shadow-cljs-edn tests
;; =============================================================================

(deftest merge-shadow-cljs-edn-test
  (testing "Merges source-paths"
    (let [result (#'scaffold/merge-shadow-cljs-edn
                  "{:source-paths [\"src\"]}"
                  "{:source-paths [\"src\" \"dev\"]}")]
      (is (= ["src" "dev"] (:source-paths result)))))

  (testing "Merges dependencies"
    (let [result (#'scaffold/merge-shadow-cljs-edn
                  "{:dependencies [[reagent \"1.2.0\"]]}"
                  "{:dependencies [[re-frame \"1.3.0\"]]}")]
      (is (= 2 (count (:dependencies result))))))

  (testing "Merges builds"
    (let [result (#'scaffold/merge-shadow-cljs-edn
                  "{:builds {:web {:target :browser}}}"
                  "{:builds {:mobile {:target :react-native}}}")]
      (is (contains? (:builds result) :web))
      (is (contains? (:builds result) :mobile))))

  (testing "Merges dev-http"
    (let [result (#'scaffold/merge-shadow-cljs-edn
                  "{:dev-http {8080 \"public\"}}"
                  "{:dev-http {9090 \"static\"}}")]
      (is (= "public" (get (:dev-http result) 8080)))
      (is (= "static" (get (:dev-http result) 9090))))))

;; =============================================================================
;; merge-package-json tests
;; =============================================================================

(deftest merge-package-json-test
  (testing "Merges scripts"
    (let [result (#'scaffold/merge-package-json
                  "{\"scripts\": {\"start\": \"node server.js\"}}"
                  "{\"scripts\": {\"test\": \"jest\"}}")]
      (is (= "node server.js" (get-in result [:scripts :start])))
      (is (= "jest" (get-in result [:scripts :test])))))

  (testing "Merges dependencies"
    (let [result (#'scaffold/merge-package-json
                  "{\"dependencies\": {\"react\": \"18.2.0\"}}"
                  "{\"dependencies\": {\"react-native\": \"0.72.0\"}}")]
      (is (= "18.2.0" (get-in result [:dependencies :react])))
      (is (= "0.72.0" (get-in result [:dependencies :react-native])))))

  (testing "Merges devDependencies"
    (let [result (#'scaffold/merge-package-json
                  "{\"devDependencies\": {\"jest\": \"29.0.0\"}}"
                  "{\"devDependencies\": {\"typescript\": \"5.0.0\"}}")]
      (is (= "29.0.0" (get-in result [:devDependencies :jest])))
      (is (= "5.0.0" (get-in result [:devDependencies :typescript])))))

  (testing "Takes name from later package"
    (let [result (#'scaffold/merge-package-json
                  "{\"name\": \"old-name\"}"
                  "{\"name\": \"new-name\"}")]
      (is (= "new-name" (:name result)))))

  (testing "Sets private to true"
    (let [result (#'scaffold/merge-package-json "{}" "{}")]
      (is (true? (:private result))))))

;; =============================================================================
;; deep-merge tests (private fn)
;; =============================================================================

(deftest deep-merge-test
  (testing "Deep merges nested maps"
    (let [result (#'scaffold/deep-merge
                  {:a {:b 1 :c 2}}
                  {:a {:c 3 :d 4}})]
      (is (= {:a {:b 1 :c 3 :d 4}} result))))

  (testing "Later values win for non-maps"
    (let [result (#'scaffold/deep-merge
                  {:a 1}
                  {:a 2})]
      (is (= {:a 2} result))))

  (testing "Handles multiple maps"
    (let [result (#'scaffold/deep-merge
                  {:a 1}
                  {:b 2}
                  {:c 3})]
      (is (= {:a 1 :b 2 :c 3} result)))))

;; =============================================================================
;; scaffold-project tests (with temp directory)
;; =============================================================================

(deftest scaffold-project-basic-test
  (testing "modules-dir returns path when running in repo"
    ;; When running tests from the forj repo, modules-dir should find the modules
    (is (some? (scaffold/modules-dir)) "modules-dir should find modules in repo context"))

  (testing "versions-file returns path when running in repo"
    (is (some? (scaffold/versions-file)) "versions-file should find versions.edn in repo context"))

  (testing "load-versions returns version data"
    (let [versions (scaffold/load-versions)]
      (is (map? versions))
      (is (contains? versions :npm))
      (is (contains? versions :clj)))))

(deftest scaffold-project-in-repo-test
  (testing "Scaffolds project with script module"
    (let [tmp-dir (str (fs/create-temp-dir {:prefix "forj-test-"}))
          result (scaffold/scaffold-project
                  {:project-name "test-script-app"
                   :modules ["script"]
                   :output-path tmp-dir})]
      (try
        (is (:success result) (str "Expected success but got: " (:error result)))
        (when (:success result)
          (let [project-dir (str (fs/path tmp-dir "test-script-app"))]
            ;; Check bb.edn exists
            (is (fs/exists? (str (fs/path project-dir "bb.edn"))))
            ;; Check CLAUDE.md generated
            (is (fs/exists? (str (fs/path project-dir "CLAUDE.md"))))))
        (finally
          (fs/delete-tree tmp-dir))))))

(comment
  ;; Run tests from REPL
  (clojure.test/run-tests 'forj.scaffold-test)

  ;; Run specific test
  (clojure.test/test-vars [#'substitute-placeholders-test])
  )
