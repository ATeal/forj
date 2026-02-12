(ns forj.lisa.plan-edn
  "EDN-based Lisa Loop plan management.

   Plans are stored as LISA_PLAN.edn files with:
   - Dependency graphs between checkpoints
   - Keyword IDs for stable references
   - Embedded signs (learnings)
   - Context compression for token efficiency

   Example plan:
   ```clojure
   {:title \"Build user authentication\"
    :status :in-progress
    :checkpoints
    [{:id :password-hashing
      :status :done
      :description \"Create password hashing module\"
      :file \"src/auth/password.clj\"
      :gates [\"repl:(verify-password \\\"test\\\" (hash-password \\\"test\\\"))\"]
      :completed \"2024-01-19T10:00:00Z\"}
     {:id :jwt-tokens
      :status :in-progress
      :depends-on [:password-hashing]
      :description \"Create JWT token module\"
      :file \"src/auth/jwt.clj\"}]
    :signs
    [{:iteration 3
      :checkpoint :jwt-tokens
      :issue \"Used wrong namespace\"
      :fix \"Use buddy.sign.jwt not buddy.core.sign\"}]
    :completed-summary \"Checkpoint 1 done: password hashing with bcrypt\"}
   ```"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def plan-filename "LISA_PLAN.edn")

;;; =============================================================================
;;; File Operations
;;; =============================================================================

(defn plan-path
  "Get the plan file path for a project."
  [project-path]
  (str (fs/path project-path plan-filename)))

(defn plan-exists?
  "Check if a plan file exists."
  [project-path]
  (fs/exists? (plan-path project-path)))

(defn read-plan
  "Read and parse a plan from disk. Returns nil if not found."
  [project-path]
  (when (plan-exists? project-path)
    (edn/read-string (slurp (plan-path project-path)))))

(defn write-plan!
  "Write a plan to disk."
  [project-path plan]
  (let [path (plan-path project-path)]
    (fs/create-dirs (fs/parent path))
    (spit path (with-out-str
                 (pprint/pprint plan)))
    plan))

;;; =============================================================================
;;; Querying
;;; =============================================================================

(defn checkpoint-by-id
  "Get a checkpoint by its keyword ID."
  [plan id]
  (first (filter #(= id (:id %)) (:checkpoints plan))))

(defn checkpoint-by-number
  "Get a checkpoint by its position (1-indexed for compatibility)."
  [plan n]
  (get (:checkpoints plan) (dec n)))

(defn current-checkpoint
  "Get the current in-progress checkpoint, or first pending if none in-progress."
  [plan]
  (or (first (filter #(= :in-progress (:status %)) (:checkpoints plan)))
      (first (filter #(= :pending (:status %)) (:checkpoints plan)))))

(defn all-complete?
  "Check if all checkpoints are done."
  [plan]
  (every? #(= :done (:status %)) (:checkpoints plan)))

;;; =============================================================================
;;; Dependency Graph
;;; =============================================================================

(defn dependencies-met?
  "Check if all dependencies of a checkpoint are satisfied (done)."
  [plan checkpoint]
  (let [deps (:depends-on checkpoint)]
    (or (empty? deps)
        (every? (fn [dep-id]
                  (= :done (:status (checkpoint-by-id plan dep-id))))
                deps))))

(defn ready-checkpoints
  "Get all checkpoints that are ready to work on (pending with deps met)."
  [plan]
  (filter (fn [cp]
            (and (= :pending (:status cp))
                 (dependencies-met? plan cp)))
          (:checkpoints plan)))

(defn blocked-checkpoints
  "Get all checkpoints that are blocked by unmet dependencies."
  [plan]
  (filter (fn [cp]
            (and (= :pending (:status cp))
                 (not (dependencies-met? plan cp))))
          (:checkpoints plan)))

(defn dependency-order
  "Return checkpoint IDs in dependency order (topological sort)."
  [plan]
  (let [checkpoints (:checkpoints plan)
        ;; Kahn's algorithm
        in-degree (into {} (map (fn [cp] [(:id cp) (count (:depends-on cp []))]) checkpoints))
        queue (filterv (fn [id] (zero? (in-degree id))) (map :id checkpoints))]
    (loop [queue queue
           result []
           in-degree in-degree]
      (if (empty? queue)
        result
        (let [node (first queue)
              rest-queue (rest queue)
              ;; Find all nodes that depend on this one
              dependents (filter (fn [cp] (some #{node} (:depends-on cp [])))
                                 checkpoints)
              ;; Decrease their in-degree
              new-in-degree (reduce (fn [deg cp]
                                      (update deg (:id cp) dec))
                                    in-degree dependents)
              ;; Add newly ready nodes to queue
              newly-ready (filter (fn [cp]
                                    (and (pos? (in-degree (:id cp)))
                                         (zero? (new-in-degree (:id cp)))))
                                  dependents)]
          (recur (into (vec rest-queue) (map :id newly-ready))
                 (conj result node)
                 new-in-degree))))))

;;; =============================================================================
;;; Mutations
;;; =============================================================================

(defn- timestamp []
  (str (java.time.Instant/now)))

(defn update-checkpoint
  "Update a checkpoint by ID with new fields. Returns updated plan."
  [plan id updates]
  (update plan :checkpoints
          (fn [cps]
            (mapv (fn [cp]
                    (if (= id (:id cp))
                      (merge cp updates)
                      cp))
                  cps))))

(defn mark-checkpoint-done!
  "Mark a checkpoint as done and advance to the next ready one."
  [project-path checkpoint-id]
  (when-let [plan (read-plan project-path)]
    (let [updated (-> plan
                      (update-checkpoint checkpoint-id
                                         {:status :done
                                          :completed (timestamp)}))]
      ;; Find next ready checkpoint and mark in-progress
      (if-let [next-ready (first (ready-checkpoints updated))]
        (-> updated
            (update-checkpoint (:id next-ready)
                               {:status :in-progress
                                :started (timestamp)})
            (assoc :status (if (all-complete? updated) :complete :in-progress))
            (->> (write-plan! project-path)))
        (-> updated
            (assoc :status (if (all-complete? updated) :complete :in-progress))
            (->> (write-plan! project-path)))))))

(defn mark-checkpoint-failed!
  "Mark a checkpoint as failed."
  [project-path checkpoint-id reason]
  (when-let [plan (read-plan project-path)]
    (-> plan
        (update-checkpoint checkpoint-id
                           {:status :failed
                            :failed-at (timestamp)
                            :failure-reason reason})
        (assoc :status :failed)
        (->> (write-plan! project-path)))))

(defn- find-checkpoint-idx
  "Find index of checkpoint by ID."
  [checkpoints id]
  (->> checkpoints
       (map-indexed vector)
       (filter #(= id (:id (second %))))
       first
       first))

(defn- find-in-progress-idx
  "Find index of current in-progress checkpoint."
  [checkpoints]
  (->> checkpoints
       (map-indexed vector)
       (filter #(= :in-progress (:status (second %))))
       first
       first))

(defn- compute-insert-position
  "Compute where to insert a checkpoint.
   Smart defaults:
   - If explicit position given, use it
   - If checkpoint has depends-on, insert after last dependency
   - If loop is running (has in-progress), insert after current
   - Otherwise append to end"
  [checkpoints checkpoint position]
  (cond
    ;; Explicit: append to end
    (= position :end)
    (count checkpoints)

    ;; Explicit: after current in-progress
    (= position :next)
    (if-let [idx (find-in-progress-idx checkpoints)]
      (inc idx)
      (count checkpoints))

    ;; Explicit: after specific checkpoint ID
    (and (keyword? position) (not= position :auto))
    (if-let [idx (find-checkpoint-idx checkpoints position)]
      (inc idx)
      (count checkpoints))

    ;; Auto: infer from context
    :else
    (cond
      ;; Has dependencies → insert after last dependency
      (seq (:depends-on checkpoint))
      (let [dep-indices (->> (:depends-on checkpoint)
                             (map #(find-checkpoint-idx checkpoints %))
                             (remove nil?))]
        (if (seq dep-indices)
          (inc (apply max dep-indices))
          (count checkpoints)))

      ;; Loop is running → insert after current in-progress
      (find-in-progress-idx checkpoints)
      (inc (find-in-progress-idx checkpoints))

      ;; Default → end
      :else
      (count checkpoints))))

(defn add-checkpoint!
  "Add a checkpoint to an existing plan.
   Position is auto-determined by default:
   - If checkpoint has depends-on → insert after last dependency
   - If loop is running (has in-progress checkpoint) → insert after current
   - Otherwise → append to end

   Explicit position options:
   - :position :end - append to end
   - :position :next - insert after current in-progress checkpoint
   - :position :<checkpoint-id> - insert after specific checkpoint

   Returns updated plan or nil if plan doesn't exist."
  [project-path checkpoint & {:keys [position] :or {position :auto}}]
  (when-let [plan (read-plan project-path)]
    (let [checkpoints (:checkpoints plan)
          new-cp (merge {:status :pending} checkpoint)
          insert-idx (compute-insert-position checkpoints new-cp position)
          updated-checkpoints (vec (concat (take insert-idx checkpoints)
                                           [new-cp]
                                           (drop insert-idx checkpoints)))
          updated-plan (-> plan
                           (assoc :checkpoints updated-checkpoints)
                           ;; Ensure status is in-progress if we're adding tasks
                           (assoc :status (if (= :complete (:status plan))
                                            :in-progress
                                            (:status plan))))]
      (write-plan! project-path updated-plan))))

;;; =============================================================================
;;; Signs (Learnings)
;;; =============================================================================

(defn append-sign!
  "Add a learning/sign to the plan."
  [project-path {:keys [iteration checkpoint issue fix severity]
                 :or {severity :error}}]
  (when-let [plan (read-plan project-path)]
    (let [sign {:iteration iteration
                :checkpoint checkpoint
                :issue issue
                :fix fix
                :severity severity
                :timestamp (timestamp)}]
      (-> plan
          (update :signs (fnil conj []) sign)
          (->> (write-plan! project-path))))))

(defn recent-signs
  "Get signs from the last N iterations."
  [plan n]
  (let [max-iter (apply max 0 (map :iteration (:signs plan [])))]
    (filter #(> (:iteration %) (- max-iter n))
            (:signs plan []))))

(defn prune-old-signs!
  "Remove signs older than N iterations."
  [project-path keep-iterations]
  (when-let [plan (read-plan project-path)]
    (let [pruned (recent-signs plan keep-iterations)]
      (-> plan
          (assoc :signs (vec pruned))
          (->> (write-plan! project-path))))))

;;; =============================================================================
;;; Context Compression (Token Efficiency)
;;; =============================================================================

(defn generate-completed-summary
  "Generate a summary of completed checkpoints for token efficiency."
  [plan]
  (let [done (filter #(= :done (:status %)) (:checkpoints plan))]
    (when (seq done)
      (str/join "; "
                (map (fn [cp]
                       (str (:id cp) ": " (:description cp)))
                     done)))))

(defn compress-plan!
  "Compress completed checkpoints into a summary for token efficiency.
   Keeps full details only for active/pending checkpoints."
  [project-path]
  (when-let [plan (read-plan project-path)]
    (let [summary (generate-completed-summary plan)
          compressed-cps (mapv (fn [cp]
                                 (if (= :done (:status cp))
                                   ;; Keep only essential fields for completed
                                   (select-keys cp [:id :status :completed])
                                   cp))
                               (:checkpoints plan))]
      (-> plan
          (assoc :checkpoints compressed-cps)
          (assoc :completed-summary summary)
          (->> (write-plan! project-path))))))

(defn context-for-iteration
  "Get plan context optimized for injection into Claude iteration.
   Returns a minimal view with:
   - Summary of completed work
   - Full details of current/pending checkpoints
   - Recent signs only"
  [plan {:keys [max-signs] :or {max-signs 5}}]
  (let [active (filter #(not= :done (:status %)) (:checkpoints plan))
        signs (take max-signs (reverse (sort-by :iteration (:signs plan []))))]
    {:title (:title plan)
     :status (:status plan)
     :completed-summary (or (:completed-summary plan)
                            (generate-completed-summary plan))
     :active-checkpoints active
     :ready (mapv :id (ready-checkpoints plan))
     :blocked (mapv (fn [cp] {:id (:id cp)
                              :blocked-by (filterv (fn [dep]
                                                     (not= :done (:status (checkpoint-by-id plan dep))))
                                                   (:depends-on cp []))})
                    (blocked-checkpoints plan))
     :recent-signs signs}))

;;; =============================================================================
;;; Gitignore Management
;;; =============================================================================

(def ^:private forj-gitignore-entries
  "Files forj generates that shouldn't be committed to user projects."
  ["LISA_PLAN.edn" ".forj/"])

(defn- gitignore-contains?
  "Check if a .gitignore file already contains an entry (exact line match)."
  [gitignore-content entry]
  (some #(= (str/trim %) entry) (str/split-lines gitignore-content)))

(defn check-gitignore
  "Check which forj artifacts are missing from the project's .gitignore.
   Returns a seq of missing entry strings, or nil if all are covered.
   Returns nil if not a git repo (nothing to worry about)."
  [project-path]
  (let [git-dir (fs/path project-path ".git")]
    (when (fs/directory? git-dir)
      (let [gitignore-path (str (fs/path project-path ".gitignore"))
            current (if (fs/exists? gitignore-path)
                      (slurp gitignore-path)
                      "")
            missing (seq (remove #(gitignore-contains? current %) forj-gitignore-entries))]
        missing))))

(defn add-to-gitignore!
  "Add forj artifact entries to the project's .gitignore.
   Only adds entries from the provided list. No-op if not a git repo."
  [project-path entries]
  (let [git-dir (fs/path project-path ".git")]
    (when (fs/directory? git-dir)
      (let [gitignore-path (str (fs/path project-path ".gitignore"))
            current (if (fs/exists? gitignore-path)
                      (slurp gitignore-path)
                      "")
            needs-newline? (and (not (str/blank? current))
                               (not (str/ends-with? current "\n")))
            section (str (when needs-newline? "\n")
                         "\n# forj (Lisa Loop runtime)\n"
                         (str/join "\n" entries)
                         "\n")]
        (spit gitignore-path (str current section))))))

;;; =============================================================================
;;; Plan Creation
;;; =============================================================================

(defn create-plan!
  "Create a new plan with checkpoints."
  [project-path {:keys [title checkpoints]}]
  (let [;; Assign IDs if not provided
        cps-with-ids (mapv (fn [cp idx]
                            (let [id (or (:id cp)
                                         (keyword (str "checkpoint-" (inc idx))))]
                              (assoc cp
                                     :id id
                                     :status (if (and (zero? idx)
                                                      (empty? (:depends-on cp)))
                                               :in-progress
                                               :pending))))
                          checkpoints (range))
        ;; Mark first ready checkpoint as in-progress
        first-ready-idx (first (keep-indexed
                                (fn [i cp]
                                  (when (empty? (:depends-on cp)) i))
                                cps-with-ids))
        final-cps (if first-ready-idx
                    (update cps-with-ids first-ready-idx
                            assoc :status :in-progress :started (timestamp))
                    cps-with-ids)
        plan {:title title
              :status :in-progress
              :checkpoints final-cps
              :signs []
              :created (timestamp)}]
    (write-plan! project-path plan)))

;;; =============================================================================
;;; REPL Exploration
;;; =============================================================================

(comment
  ;; Create a plan with dependencies
  (create-plan! "/tmp/test-edn"
                {:title "Build user authentication"
                 :checkpoints [{:id :password-hashing
                                :description "Create password hashing module"
                                :file "src/auth/password.clj"
                                :gates ["repl:(verify-password \"test\" (hash-password \"test\"))"]}
                               {:id :jwt-tokens
                                :description "Create JWT token module"
                                :file "src/auth/jwt.clj"
                                :depends-on [:password-hashing]
                                :gates ["repl:(verify-token (create-token {:user-id 1}))"]}
                               {:id :auth-middleware
                                :description "Create auth middleware"
                                :file "src/middleware/auth.clj"
                                :depends-on [:jwt-tokens]}]})

  ;; Read it back
  (read-plan "/tmp/test-edn")

  ;; Check dependency order
  (dependency-order (read-plan "/tmp/test-edn"))

  ;; Get ready checkpoints
  (ready-checkpoints (read-plan "/tmp/test-edn"))

  ;; Mark checkpoint done
  (mark-checkpoint-done! "/tmp/test-edn" :password-hashing)

  ;; Check what's ready now
  (ready-checkpoints (read-plan "/tmp/test-edn"))

  ;; Add a sign
  (append-sign! "/tmp/test-edn"
                {:iteration 1
                 :checkpoint :jwt-tokens
                 :issue "Wrong import"
                 :fix "Use buddy.sign.jwt"})

  ;; Get context for iteration (token-efficient)
  (context-for-iteration (read-plan "/tmp/test-edn") {})

  ;; Compress completed checkpoints
  (compress-plan! "/tmp/test-edn"))
