(ns forj.lisa.plan
  "Lisa Loop plan file management.

   Plans are stored as LISA_PLAN.md files in the project root.
   Format:

   # Lisa Loop Plan: <title>

   ## Status: IN_PROGRESS | COMPLETE | FAILED
   ## Current Checkpoint: <number>

   ## Checkpoints

   ### 1. [DONE] <description>
   - File: <path>
   - Acceptance: <criteria>
   - Completed: <timestamp>

   ### 2. [IN_PROGRESS] <description>
   - File: <path>
   - Acceptance: <criteria>
   - Started: <timestamp>

   ### 3. [PENDING] <description>
   - File: <path>
   - Acceptance: <criteria>"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def plan-filename "LISA_PLAN.md")

(defn plan-path
  "Get the plan file path for a project."
  [project-path]
  (str (fs/path project-path plan-filename)))

(defn plan-exists?
  "Check if a plan file exists."
  [project-path]
  (fs/exists? (plan-path project-path)))

;;; Parsing

(defn- parse-checkpoint-status
  "Parse status from checkpoint header like '### 1. [DONE] Do something'"
  [line]
  (cond
    (str/includes? line "[DONE]") :done
    (str/includes? line "[IN_PROGRESS]") :in-progress
    (str/includes? line "[PENDING]") :pending
    (str/includes? line "[FAILED]") :failed
    :else :pending))

(defn- parse-checkpoint-header
  "Parse checkpoint header line.
   Returns {:number 1 :status :done :description \"Do something\"}"
  [line]
  (when-let [[_ num status-str desc] (re-matches #"###\s+(\d+)\.\s+\[(\w+(?:_\w+)?)\]\s+(.*)" line)]
    {:number (parse-long num)
     :status (keyword (str/lower-case (str/replace status-str "_" "-")))
     :description (str/trim desc)}))

(defn- parse-checkpoint-detail
  "Parse a checkpoint detail line like '- File: src/foo.clj'"
  [line]
  (when-let [[_ key value] (re-matches #"-\s+(\w+):\s+(.*)" line)]
    [(keyword (str/lower-case key)) (str/trim value)]))

(defn- parse-metadata
  "Parse plan metadata from header lines."
  [lines]
  (let [title-line (first (filter #(str/starts-with? % "# Lisa Loop Plan:") lines))
        status-line (first (filter #(str/starts-with? % "## Status:") lines))
        current-line (first (filter #(str/starts-with? % "## Current Checkpoint:") lines))]
    {:title (when title-line
              (str/trim (subs title-line (count "# Lisa Loop Plan:"))))
     :status (when status-line
               (keyword (str/lower-case
                         (str/replace
                          (str/trim (subs status-line (count "## Status:")))
                          "_" "-"))))
     :current-checkpoint (when current-line
                           (parse-long (str/trim (subs current-line (count "## Current Checkpoint:")))))}))

(defn- parse-checkpoints
  "Parse all checkpoints from plan lines."
  [lines]
  (loop [remaining lines
         checkpoints []
         current-checkpoint nil]
    (if (empty? remaining)
      (if current-checkpoint
        (conj checkpoints current-checkpoint)
        checkpoints)
      (let [line (first remaining)
            rest-lines (rest remaining)]
        (if-let [header (parse-checkpoint-header line)]
          ;; New checkpoint header
          (recur rest-lines
                 (if current-checkpoint
                   (conj checkpoints current-checkpoint)
                   checkpoints)
                 header)
          ;; Might be a detail line
          (if-let [[k v] (parse-checkpoint-detail line)]
            (recur rest-lines checkpoints (assoc current-checkpoint k v))
            (recur rest-lines checkpoints current-checkpoint)))))))

(defn parse-plan
  "Parse a LISA_PLAN.md file into a data structure."
  [project-path]
  (when (plan-exists? project-path)
    (let [content (slurp (plan-path project-path))
          lines (str/split-lines content)
          metadata (parse-metadata lines)
          checkpoints (parse-checkpoints lines)]
      (assoc metadata :checkpoints checkpoints))))

;;; Querying

(defn current-checkpoint
  "Get the current in-progress checkpoint, or the first pending if none in-progress."
  [plan]
  (or (first (filter #(= :in-progress (:status %)) (:checkpoints plan)))
      (first (filter #(= :pending (:status %)) (:checkpoints plan)))))

(defn all-complete?
  "Check if all checkpoints are done."
  [plan]
  (every? #(= :done (:status %)) (:checkpoints plan)))

(defn checkpoint-by-number
  "Get a checkpoint by its number."
  [plan n]
  (first (filter #(= n (:number %)) (:checkpoints plan))))

;;; Writing

(defn- checkpoint->markdown
  "Convert a checkpoint map to markdown lines."
  [{:keys [number status description file acceptance started completed gates validation]}]
  (let [status-str (str/upper-case (name status))
        status-str (str/replace status-str "-" "_")]
    (str/join "\n"
              (filter some?
                      [(str "### " number ". [" status-str "] " description)
                       (when file (str "- File: " file))
                       (when acceptance (str "- Acceptance: " acceptance))
                       (when gates (str "- Gates: " gates))
                       (when validation (str "- Validation: " validation))
                       (when started (str "- Started: " started))
                       (when completed (str "- Completed: " completed))]))))

(defn plan->markdown
  "Convert a plan data structure to markdown."
  [{:keys [title status current-checkpoint checkpoints]}]
  (let [status-str (str/upper-case (name (or status :in-progress)))
        status-str (str/replace status-str "-" "_")]
    (str/join "\n\n"
              [(str "# Lisa Loop Plan: " title)
               (str "## Status: " status-str)
               (str "## Current Checkpoint: " (or current-checkpoint 1))
               "## Checkpoints"
               (str/join "\n\n" (map checkpoint->markdown checkpoints))])))

(defn write-plan!
  "Write a plan to the LISA_PLAN.md file."
  [project-path plan]
  (let [path (plan-path project-path)]
    (fs/create-dirs (fs/parent path))
    (spit path (plan->markdown plan))
    plan))

;;; Mutations

(defn mark-checkpoint-done!
  "Mark a checkpoint as done and advance to the next one."
  [project-path checkpoint-number]
  (when-let [plan (parse-plan project-path)]
    (let [timestamp (str (java.time.Instant/now))
          updated-checkpoints
          (mapv (fn [cp]
                  (cond
                    (= (:number cp) checkpoint-number)
                    (-> cp
                        (assoc :status :done)
                        (assoc :completed timestamp))

                    (= (:number cp) (inc checkpoint-number))
                    (-> cp
                        (assoc :status :in-progress)
                        (assoc :started timestamp))

                    :else cp))
                (:checkpoints plan))

          next-checkpoint (inc checkpoint-number)
          all-done? (every? #(= :done (:status %)) updated-checkpoints)

          updated-plan
          (-> plan
              (assoc :checkpoints updated-checkpoints)
              (assoc :current-checkpoint (if all-done? checkpoint-number next-checkpoint))
              (assoc :status (if all-done? :complete :in-progress)))]

      (write-plan! project-path updated-plan))))

(defn mark-checkpoint-failed!
  "Mark a checkpoint as failed."
  [project-path checkpoint-number reason]
  (when-let [plan (parse-plan project-path)]
    (let [timestamp (str (java.time.Instant/now))
          updated-checkpoints
          (mapv (fn [cp]
                  (if (= (:number cp) checkpoint-number)
                    (-> cp
                        (assoc :status :failed)
                        (assoc :failed-at timestamp)
                        (assoc :failure-reason reason))
                    cp))
                (:checkpoints plan))

          updated-plan
          (-> plan
              (assoc :checkpoints updated-checkpoints)
              (assoc :status :failed))]

      (write-plan! project-path updated-plan))))

(defn create-plan!
  "Create a new plan with checkpoints."
  [project-path {:keys [title checkpoints]}]
  (let [plan {:title title
              :status :in-progress
              :current-checkpoint 1
              :checkpoints (vec (map-indexed
                                 (fn [idx cp]
                                   (merge {:number (inc idx)
                                           :status (if (zero? idx) :in-progress :pending)}
                                          cp))
                                 checkpoints))}]
    (write-plan! project-path plan)))

(comment
  ;; Test expressions

  ;; Create a plan
  (create-plan! "/tmp/test-project"
                {:title "Build user authentication"
                 :checkpoints [{:description "Create password hashing module"
                                :file "src/auth/password.clj"
                                :acceptance "(verify-password \"test\" (hash-password \"test\")) => true"}
                               {:description "Create JWT token module"
                                :file "src/auth/jwt.clj"
                                :acceptance "(verify-token (create-token {:user-id 1})) => {:user-id 1}"}
                               {:description "Create auth middleware"
                                :file "src/middleware/auth.clj"
                                :acceptance "Middleware extracts user from valid token"}]})

  ;; Parse plan
  (parse-plan "/tmp/test-project")

  ;; Get current checkpoint
  (current-checkpoint (parse-plan "/tmp/test-project"))

  ;; Mark checkpoint done
  (mark-checkpoint-done! "/tmp/test-project" 1)

  ;; Check if complete
  (all-complete? (parse-plan "/tmp/test-project"))
  )
