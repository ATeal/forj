(ns forj.scaffold
  "Project scaffolding with composable modules."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [cheshire.core :as json]))

;; =============================================================================
;; Paths
;; =============================================================================

(defn- find-forj-root
  "Find the forj installation root.
   Returns a map with :type (:repo or :installed) and :path."
  []
  (or
   ;; Explicit env var
   (when-let [root (System/getenv "FORJ_ROOT")]
     {:type :env :path root})
   ;; Check if we're in the forj repo
   (when (fs/exists? "packages/forj-skill/clj-init/modules")
     {:type :repo :path "."})
   ;; Check home directory installation (most common case when running via MCP)
   (let [home-clj-init (str (fs/path (System/getProperty "user.home")
                                     ".claude" "skills" "clj-init"))]
     (when (fs/exists? (str (fs/path home-clj-init "modules")))
       {:type :installed :path home-clj-init}))
   ;; Fall back to discovering from script path
   (when-let [script-path (fs/which "forj-mcp")]
     (let [forj-root (str (fs/parent (fs/parent script-path)))]
       (when (fs/exists? (str (fs/path forj-root "packages" "forj-skill" "clj-init" "modules")))
         {:type :repo :path forj-root})))))

(defn modules-dir
  "Get path to modules directory."
  []
  (when-let [{:keys [type path]} (find-forj-root)]
    (let [modules-path (case type
                         :repo (if (= path ".")
                                 "packages/forj-skill/clj-init/modules"
                                 (str (fs/path path "packages" "forj-skill" "clj-init" "modules")))
                         :installed (str (fs/path path "modules"))
                         :env (str (fs/path path "modules")))]
      (when (fs/exists? modules-path) modules-path))))

(defn versions-file
  "Get path to versions.edn."
  []
  (when-let [{:keys [type path]} (find-forj-root)]
    (let [versions-path (case type
                          :repo (if (= path ".")
                                  "packages/forj-skill/clj-init/versions.edn"
                                  (str (fs/path path "packages" "forj-skill" "clj-init" "versions.edn")))
                          :installed (str (fs/path path "versions.edn"))
                          :env (str (fs/path path "versions.edn")))]
      (when (fs/exists? versions-path) versions-path))))

;; =============================================================================
;; Version Resolution
;; =============================================================================

(defn- get-java-version
  "Get current Java major version."
  []
  (try
    (let [result (p/shell {:out :string :err :string :continue true}
                          "java" "-version")
          version-str (or (:err result) (:out result))
          version-match (re-find #"version \"(\d+)" version-str)]
      (when version-match (parse-long (second version-match))))
    (catch Exception _ nil)))

(defn- resolve-conditional-version
  "Resolve a version spec that may have conditions."
  [version-spec java-version]
  (if-let [versions (:versions version-spec)]
    ;; Conditional versions
    (let [match (some (fn [{:keys [version when]}]
                        (let [{java-cond :java} when]
                          (when (cond
                                  (nil? java-cond) true
                                  (str/starts-with? java-cond ">=")
                                  (>= (or java-version 0) (parse-long (str/trim (subs java-cond 2))))
                                  (str/starts-with? java-cond "<")
                                  (< (or java-version 99) (parse-long (str/trim (subs java-cond 1))))
                                  :else true)
                            version)))
                      versions)]
      (or match (:default version-spec)))
    ;; Simple version
    (:version version-spec)))

(defn load-versions
  "Load and resolve versions from versions.edn."
  []
  (when-let [vfile (versions-file)]
    (when (fs/exists? vfile)
      (let [versions (edn/read-string (slurp vfile))
            java-version (get-java-version)]
        ;; Flatten into lookup maps
        {:npm (reduce-kv (fn [m k v]
                           (assoc m (name k) (resolve-conditional-version v java-version)))
                         {}
                         (:npm versions))
         :clj (reduce-kv (fn [m k v]
                           (let [coord (if (namespace k)
                                         (str (namespace k) "/" (name k))
                                         (name k))]
                             (assoc m coord (:version v))))
                         {}
                         (:clojure versions))
         :cljs (reduce-kv (fn [m k v]
                            (let [coord (if (namespace k)
                                          (str (namespace k) "/" (name k))
                                          (name k))]
                              (assoc m coord (:version v))))
                          {}
                          (:clojurescript versions))}))))

;; =============================================================================
;; Placeholder Substitution
;; =============================================================================

(defn- project-name->namespace
  "Convert project name to Clojure namespace format."
  [project-name]
  (-> project-name
      (str/replace "-" "_")
      (str/replace "." "_")))

(defn substitute-placeholders
  "Replace all placeholders in content."
  [content project-name versions]
  (let [namespace (project-name->namespace project-name)]
    (-> content
        ;; Project placeholders
        (str/replace "{{project-name}}" project-name)
        (str/replace "{{namespace}}" namespace)
        ;; NPM version placeholders: {{npm:package-name}}
        (str/replace #"\{\{npm:([^}]+)\}\}"
                     (fn [[_ pkg]]
                       (or (get-in versions [:npm pkg])
                           (do (println "Warning: Unknown npm package:" pkg)
                               "0.0.0"))))
        ;; Clojure version placeholders: {{clj:group/artifact}}
        (str/replace #"\{\{clj:([^}]+)\}\}"
                     (fn [[_ coord]]
                       (or (get-in versions [:clj coord])
                           (do (println "Warning: Unknown clj dep:" coord)
                               "0.0.0"))))
        ;; ClojureScript version placeholders: {{cljs:group/artifact}}
        (str/replace #"\{\{cljs:([^}]+)\}\}"
                     (fn [[_ coord]]
                       (or (get-in versions [:cljs coord])
                           (do (println "Warning: Unknown cljs dep:" coord)
                               "0.0.0")))))))

;; =============================================================================
;; Config Merging
;; =============================================================================

(defn- deep-merge
  "Deep merge maps. Later values win for non-map values."
  [& maps]
  (reduce (fn [acc m]
            (reduce-kv (fn [a k v]
                         (let [existing (get a k)]
                           (assoc a k (if (and (map? existing) (map? v))
                                        (deep-merge existing v)
                                        v))))
                       acc
                       m))
          {}
          maps))

(defn- merge-deps-edn
  "Merge multiple deps.edn contents."
  [& contents]
  (let [parsed (map edn/read-string contents)]
    (reduce (fn [acc deps]
              (-> acc
                  (update :paths #(vec (distinct (concat % (:paths deps)))))
                  (update :deps merge (:deps deps))
                  (update :aliases deep-merge (:aliases deps))))
            {:paths [] :deps {} :aliases {}}
            parsed)))

(defn- merge-bb-edn
  "Merge multiple bb.edn contents."
  [& contents]
  (let [parsed (map edn/read-string contents)]
    (reduce (fn [acc bb]
              (-> acc
                  (update :paths #(vec (distinct (concat % (:paths bb)))))
                  (update :tasks merge (:tasks bb))))
            {:paths [] :tasks {}}
            parsed)))

(defn- merge-shadow-cljs-edn
  "Merge multiple shadow-cljs.edn contents."
  [& contents]
  (let [parsed (map edn/read-string contents)]
    (reduce (fn [acc shadow]
              (-> acc
                  (update :source-paths #(vec (distinct (concat % (:source-paths shadow)))))
                  (update :dependencies #(vec (distinct (concat % (:dependencies shadow)))))
                  (update :builds merge (:builds shadow))))
            {:source-paths [] :dependencies [] :builds {}}
            parsed)))

(defn- merge-package-json
  "Merge multiple package.json contents."
  [& contents]
  (let [parsed (map #(json/parse-string % true) contents)]
    (reduce (fn [acc pkg]
              (-> acc
                  (assoc :name (or (:name pkg) (:name acc)))
                  (assoc :version (or (:version pkg) (:version acc) "0.0.1"))
                  (assoc :main (or (:main pkg) (:main acc)))
                  (assoc :private true)
                  (update :scripts merge (:scripts pkg))
                  (update :dependencies merge (:dependencies pkg))
                  (update :devDependencies merge (:devDependencies pkg))))
            {:scripts {} :dependencies {} :devDependencies {}}
            parsed)))

;; =============================================================================
;; Module Loading
;; =============================================================================

(defn- load-module
  "Load a module definition."
  [module-name]
  (let [module-dir (str (fs/path (modules-dir) module-name))
        module-file (str (fs/path module-dir "module.edn"))]
    (when (fs/exists? module-file)
      (assoc (edn/read-string (slurp module-file))
             :dir module-dir))))

(defn- resolve-module-deps
  "Resolve module dependencies recursively."
  [module-names]
  (loop [to-process (vec module-names)
         resolved []
         seen #{}]
    (if (empty? to-process)
      (distinct resolved)
      (let [mod-name (first to-process)
            remaining (rest to-process)]
        (if (seen mod-name)
          (recur remaining resolved seen)
          (let [mod (load-module mod-name)
                deps (:requires mod [])]
            (recur (concat deps remaining [mod-name])
                   (conj resolved mod-name)
                   (conj seen mod-name))))))))

;; =============================================================================
;; File Generation
;; =============================================================================

(defn- format-edn
  "Format EDN for human-readable output."
  [data]
  (with-out-str
    (pprint/pprint data)))

(defn- collect-module-files
  "Collect all files from modules for a given config type.
   Substitutes placeholders before returning."
  [modules config-type project-name versions]
  (for [mod modules
        :let [module-def (load-module mod)
              merge-config (:merge module-def)
              file-name (get merge-config config-type)]
        :when file-name
        :let [file-path (str (fs/path (:dir module-def) file-name))]
        :when (fs/exists? file-path)]
    ;; Substitute placeholders BEFORE EDN parsing
    (substitute-placeholders (slurp file-path) project-name versions)))

(defn scaffold-project
  "Scaffold a new project from modules.

   Arguments:
   - project-name: Name of the project (e.g., 'my-app')
   - modules: Vector of module names (e.g., ['backend' 'mobile' 'db-postgres'])
   - output-path: Directory to create project in (default: current dir)"
  [{:keys [project-name modules output-path]
    :or {output-path "."}}]
  (try
    (let [project-dir (str (fs/path output-path project-name))
          _ (fs/create-dirs project-dir)

          ;; Resolve module dependencies
          all-modules (resolve-module-deps modules)
          _ (println "Scaffolding with modules:" (str/join ", " all-modules))

          ;; Load versions
          versions (load-versions)
          _ (when-not versions
              (println "Warning: versions.edn not found, using placeholder versions"))

          ;; Collect and merge configs (placeholders substituted before parse)
          deps-contents (collect-module-files all-modules :deps.edn project-name versions)
          bb-contents (collect-module-files all-modules :bb.edn project-name versions)
          shadow-contents (collect-module-files all-modules :shadow-cljs.edn project-name versions)
          pkg-contents (collect-module-files all-modules :package.json project-name versions)

          ;; Track what files to write
          gitignore-entries []]

      ;; Write deps.edn if any module provides it
      (when (seq deps-contents)
        (let [merged (apply merge-deps-edn deps-contents)]
          (spit (str (fs/path project-dir "deps.edn")) (format-edn merged))))

      ;; Write bb.edn if any module provides it
      (when (seq bb-contents)
        (let [merged (apply merge-bb-edn bb-contents)]
          (spit (str (fs/path project-dir "bb.edn")) (format-edn merged))))

      ;; Write shadow-cljs.edn if any module provides it
      (when (seq shadow-contents)
        (let [merged (apply merge-shadow-cljs-edn shadow-contents)]
          (spit (str (fs/path project-dir "shadow-cljs.edn")) (format-edn merged))))

      ;; Write package.json if any module provides it
      (when (seq pkg-contents)
        (let [merged (apply merge-package-json pkg-contents)]
          (spit (str (fs/path project-dir "package.json"))
                (json/generate-string merged {:pretty true}))))

      ;; Copy static files and sources from each module
      (doseq [mod-name all-modules]
        (let [mod (load-module mod-name)
              mod-dir (:dir mod)]

          ;; Copy renamed files (e.g., gitignore -> .gitignore)
          (doseq [file (:files mod)]
            (let [src (str (fs/path mod-dir file))
                  dest-name (get (:rename mod) file file)
                  dest (str (fs/path project-dir dest-name))]
              (when (fs/exists? src)
                (let [content (substitute-placeholders (slurp src) project-name versions)]
                  (fs/create-dirs (fs/parent dest))
                  (spit dest content)))))

          ;; Copy source files with path substitution
          (doseq [{:keys [from to]} (:sources mod)]
            (let [src (str (fs/path mod-dir from))
                  dest (str (fs/path project-dir
                                     (substitute-placeholders to project-name versions)))]
              (when (fs/exists? src)
                (let [content (substitute-placeholders (slurp src) project-name versions)]
                  (fs/create-dirs (fs/parent dest))
                  (spit dest content)))))

          ;; Collect gitignore entries
          (when-let [entries (:gitignore-append mod)]
            (doseq [entry entries]
              (when (not (str/blank? entry))
                (conj gitignore-entries entry))))))

      ;; Append to .gitignore
      (let [gitignore-file (str (fs/path project-dir ".gitignore"))]
        (when (and (fs/exists? gitignore-file) (seq gitignore-entries))
          (spit gitignore-file
                (str (slurp gitignore-file) "\n" (str/join "\n" gitignore-entries) "\n")
                :append true)))

      {:success true
       :project-dir project-dir
       :modules (vec all-modules)
       :message (str "Created " project-name " with modules: " (str/join ", " all-modules))})

    (catch Exception e
      {:success false
       :error (str "Scaffold failed: " (.getMessage e))})))

(comment
  ;; Test scaffolding
  (scaffold-project {:project-name "test-app"
                     :modules ["backend" "mobile"]
                     :output-path "/tmp"})

  ;; Check modules dir
  (modules-dir)
  (fs/exists? (modules-dir))

  ;; Check versions
  (load-versions)
  )
