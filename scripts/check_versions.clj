(ns scripts.check-versions
  "Check template dependency versions against latest available.

   Usage: bb check-versions [--update]"
  (:require [babashka.http-client :as http]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def versions-file "packages/forj-skill/clj-init/versions.edn")

(defn parse-version
  "Extract numeric version from version string (strips ^, ~, etc.)"
  [v]
  (when v
    (-> v
        (str/replace #"^[\^~>=<]+" "")
        (str/replace #"-.*$" ""))))  ; Strip prerelease tags

(defn npm-latest
  "Fetch latest version from npm registry."
  [package-name]
  (try
    (let [url (str "https://registry.npmjs.org/" package-name "/latest")
          response (http/get url {:throw false})]
      (when (= 200 (:status response))
        (-> response :body (json/parse-string true) :version)))
    (catch Exception _
      nil)))

(defn keyword->coordinate
  "Convert a keyword like :com.github.seancorfield/honeysql to coordinate string."
  [k]
  (let [ns-part (namespace k)
        name-part (name k)]
    (if ns-part
      (str ns-part "/" name-part)
      name-part)))

(defn clojars-latest
  "Fetch latest version from Clojars."
  [group-artifact]
  (try
    (let [ga-str (if (keyword? group-artifact)
                   (keyword->coordinate group-artifact)
                   (str group-artifact))
          ;; Handle both "group/artifact" and "artifact" (where group=artifact)
          [group artifact] (if (str/includes? ga-str "/")
                             (str/split ga-str #"/")
                             [ga-str ga-str])
          url (str "https://clojars.org/api/artifacts/" group "/" artifact)
          response (http/get url {:throw false})]
      (when (= 200 (:status response))
        (-> response :body (json/parse-string true) :latest_version)))
    (catch Exception _
      nil)))

(defn maven-latest
  "Fetch latest version from Maven Central (for non-Clojars deps)."
  [group-artifact]
  (try
    (let [ga-str (if (keyword? group-artifact)
                   (keyword->coordinate group-artifact)
                   (str group-artifact))
          [group artifact] (if (str/includes? ga-str "/")
                             (str/split ga-str #"/")
                             [ga-str ga-str])
          ;; Maven search requires dots in group ID
          url (str "https://search.maven.org/solrsearch/select?q=g:" group "+AND+a:" artifact "&rows=1&wt=json")
          response (http/get url {:throw false})]
      (when (= 200 (:status response))
        (-> response :body (json/parse-string true) :response :docs first :latestVersion)))
    (catch Exception _
      nil)))

(defn snapshot?
  "Check if version is a SNAPSHOT."
  [v]
  (when v (str/includes? (str/upper-case v) "SNAPSHOT")))

(defn version-newer?
  "Check if v2 is newer than v1 (simple semver comparison).
   Ignores SNAPSHOT versions."
  [v1 v2]
  (when (and v1 v2 (not (snapshot? v2)))
    (let [parse (fn [v]
                  (->> (str/split (parse-version v) #"\.")
                       (map #(try (parse-long %) (catch Exception _ 0)))
                       vec))
          [a1 b1 c1] (parse v1)
          [a2 b2 c2] (parse v2)]
      (or (> (or a2 0) (or a1 0))
          (and (= a2 a1) (> (or b2 0) (or b1 0)))
          (and (= a2 a1) (= b2 b1) (> (or c2 0) (or c1 0)))))))

(defn get-java-version
  "Get current Java major version."
  []
  (try
    (let [result (-> (Runtime/getRuntime)
                     (.exec (into-array String ["java" "-version"])))]
      (with-open [reader (java.io.BufferedReader.
                          (java.io.InputStreamReader. (.getErrorStream result)))]
        (let [version-line (.readLine reader)
              match (re-find #"version \"(\d+)" version-line)]
          (when match (parse-long (second match))))))
    (catch Exception _ nil)))

(defn resolve-conditional-version
  "Resolve conditional version based on current environment."
  [version-spec]
  (if-let [versions (:versions version-spec)]
    ;; Conditional versions
    (let [java-version (get-java-version)
          match (some (fn [{:keys [version when]}]
                        (let [{java-cond :java} when]
                          (when (cond
                                  (nil? java-cond) true
                                  (str/starts-with? java-cond ">=")
                                  (>= (or java-version 0) (parse-long (subs java-cond 3)))
                                  (str/starts-with? java-cond "<")
                                  (< (or java-version 99) (parse-long (subs java-cond 2)))
                                  :else true)
                            version)))
                      versions)]
      (or match (:default version-spec)))
    ;; Simple version
    (:version version-spec)))

(defn check-npm-versions
  "Check all npm dependencies for updates."
  [npm-deps]
  (println "\n📦 Checking npm packages...\n")
  (let [results
        (for [[k spec] npm-deps]
          (let [pkg-name (or (:npm-package spec) (name k))
                current (resolve-conditional-version spec)
                latest (npm-latest pkg-name)
                outdated? (version-newer? current latest)]
            {:name pkg-name
             :key k
             :current current
             :latest latest
             :outdated? outdated?
             :notes (:notes spec)
             :conditional? (boolean (:versions spec))}))]
    (doseq [{:keys [name current latest outdated? notes conditional?]} results]
      (let [status (cond
                     (nil? latest) "⚠️  (fetch failed)"
                     outdated? "⬆️  UPDATE"
                     :else "✓")]
        (println (format "  %-35s %s → %s %s%s"
                         name
                         (or current "?")
                         (or latest "?")
                         status
                         (if conditional? " (conditional)" "")))))
    results))

(defn check-clojure-versions
  "Check all Clojure dependencies for updates."
  [clj-deps]
  (println "\n📚 Checking Clojure deps...\n")
  (let [results
        (for [[k spec] clj-deps]
          (let [dep-name (name k)
                current (:version spec)
                ;; Try Clojars first, then Maven
                latest (or (clojars-latest k) (maven-latest k))]
            {:name dep-name
             :key k
             :current current
             :latest latest
             :outdated? (version-newer? current latest)}))]
    (doseq [{:keys [name current latest outdated?]} results]
      (let [status (cond
                     (nil? latest) "⚠️  (fetch failed)"
                     outdated? "⬆️  UPDATE"
                     :else "✓")]
        (println (format "  %-45s %s → %s %s"
                         name
                         (or current "?")
                         (or latest "?")
                         status))))
    results))

(defn summarize
  "Print summary of outdated packages."
  [npm-results clj-results cljs-results]
  (let [all-outdated (->> (concat npm-results clj-results cljs-results)
                          (filter :outdated?))]
    (println "\n" (str (apply str (repeat 60 "─"))))
    (if (seq all-outdated)
      (do
        (println (format "\n⚠️  %d package(s) can be updated:\n" (count all-outdated)))
        (doseq [{:keys [name current latest]} all-outdated]
          (println (format "  • %s: %s → %s" name current latest)))
        (println "\nEdit versions.edn to update, then run `bb install` to reinstall skills."))
      (println "\n✓ All packages are up to date!"))
    (println)))

(defn -main
  [& _args]
  (if-not (fs/exists? versions-file)
    (do
      (println "❌ versions.edn not found at" versions-file)
      (System/exit 1))
    (let [versions (edn/read-string (slurp versions-file))
          java-v (get-java-version)]
      (println "🔍 Checking template dependency versions...")
      (println (format "   Java version: %s" (or java-v "not detected")))

      (let [npm-results (check-npm-versions (:npm versions))
            clj-results (check-clojure-versions (:clojure versions))
            cljs-results (check-clojure-versions (:clojurescript versions))]
        (summarize npm-results clj-results cljs-results)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
