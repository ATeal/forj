(ns forj.lisa.review
  "Post-completion review for Lisa Loop runs.

   Gathers all artifacts (plan, signs, git diff stats, per-checkpoint metadata)
   and produces a structured data map + formatted terminal summary.
   No LLM calls — fast, deterministic, data-only."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [forj.lisa.analytics :as analytics]
            [forj.lisa.plan-edn :as plan-edn]))

;;; =============================================================================
;;; Helpers
;;; =============================================================================

(defn- calculate-duration-ms
  "Calculate duration in milliseconds from ISO timestamp strings."
  [started-at completed-at]
  (when (and started-at completed-at)
    (try
      (let [start (java.time.Instant/parse started-at)
            end (java.time.Instant/parse completed-at)]
        (.toMillis (java.time.Duration/between start end)))
      (catch Exception _ nil))))

(defn- format-duration
  "Format milliseconds as human-readable duration (e.g., '2m 10s', '42s')."
  [ms]
  (when ms
    (let [total-secs (quot ms 1000)
          mins (quot total-secs 60)
          secs (rem total-secs 60)]
      (cond
        (zero? mins) (str secs "s")
        (zero? secs) (str mins "m")
        :else (str mins "m " secs "s")))))

(defn- format-tokens
  "Format token count with K/M suffix."
  [n]
  (cond
    (nil? n) "0"
    (>= n 1000000) (format "%.1fM" (/ n 1000000.0))
    (>= n 1000) (format "%.1fK" (/ n 1000.0))
    :else (str n)))

(defn- git-shell
  "Run a git command in project-path, return stdout or nil on failure."
  [project-path & args]
  (try
    (let [result (apply p/shell {:dir project-path :out :string :err :string :continue true}
                        "git" args)]
      (when (zero? (:exit result))
        (str/trim (:out result))))
    (catch Exception _ nil)))

;;; =============================================================================
;;; Git Data
;;; =============================================================================

(defn- parse-diff-stat
  "Parse `git diff --stat` output into {:files-changed N :insertions N :deletions N}."
  [stat-output]
  (when stat-output
    (let [summary-line (last (str/split-lines stat-output))]
      (when summary-line
        (let [files (when-let [m (re-find #"(\d+) files? changed" summary-line)]
                      (parse-long (second m)))
              ins (when-let [m (re-find #"(\d+) insertions?" summary-line)]
                    (parse-long (second m)))
              del (when-let [m (re-find #"(\d+) deletions?" summary-line)]
                    (parse-long (second m)))]
          {:files-changed (or files 0)
           :insertions (or ins 0)
           :deletions (or del 0)})))))

(defn- get-lisa-commits
  "Get Lisa checkpoint commits from git log. Returns vec of {:sha :message :checkpoint-id}."
  [project-path]
  (when-let [log-output (git-shell project-path
                                   "log" "--oneline" "--grep=Lisa: Checkpoint" "--format=%h %s")]
    (when-not (str/blank? log-output)
      (->> (str/split-lines log-output)
           (mapv (fn [line]
                   (let [[sha & msg-parts] (str/split line #"\s+" 2)
                         message (first msg-parts)
                         ;; Extract checkpoint ID from "Lisa: Checkpoint :id - desc"
                         cp-match (re-find #"Lisa: Checkpoint (:[\w-]+)" (or message ""))]
                     {:sha sha
                      :message message
                      :checkpoint-id (when cp-match
                                       (keyword (subs (second cp-match) 1)))})))))))

(defn- get-diff-stat-between
  "Get diff stat between two commits, or from first Lisa commit to HEAD."
  [project-path commits]
  (when (seq commits)
    (let [first-sha (:sha (last commits))
          ;; Get the parent of the first Lisa commit for full diff
          stat-output (git-shell project-path "diff" "--stat" (str first-sha "^...HEAD"))]
      (when stat-output
        (assoc (parse-diff-stat stat-output)
               :diff-stat stat-output)))))

(defn- get-per-commit-diff
  "Get diff stat for a specific commit. Returns {:files-changed [...] :stat-line '...'}."
  [project-path sha]
  (when-let [output (git-shell project-path "diff-tree" "--no-commit-id" "-r" "--stat" sha)]
    (let [lines (str/split-lines output)
          file-lines (butlast lines)
          summary-line (last lines)]
      {:files (mapv (fn [line]
                      (let [[path _] (str/split (str/trim line) #"\s*\|\s*")]
                        (str/trim path)))
                    (or file-lines []))
       :stat-line (or summary-line "")})))

;;; =============================================================================
;;; Meta File Data
;;; =============================================================================

(defn- read-meta-files
  "Read all meta files from the log directory. Returns seq of parsed meta maps."
  [_project-path log-dir]
  (when (fs/exists? log-dir)
    (->> (fs/glob log-dir "*-meta.json")
         (mapv (fn [f]
                 (try
                   (json/parse-string (slurp (str f)) true)
                   (catch Exception _ nil))))
         (filter some?))))

(defn- meta-files-by-checkpoint
  "Group meta files by checkpoint-id keyword."
  [meta-files]
  (->> meta-files
       (group-by #(when (:checkpoint-id %) (keyword (:checkpoint-id %))))
       (into {})))

(defn- checkpoint-meta-summary
  "Summarize meta files for a single checkpoint."
  [metas]
  (let [iterations (count metas)
        started (some :started-at metas)
        completed (->> metas (filter :completed-at) (sort-by :completed-at) last :completed-at)
        duration (calculate-duration-ms started completed)
        successes (count (filter :success metas))
        errors (->> metas (filter :error) (map :error) (remove nil?))]
    {:iterations iterations
     :started started
     :completed completed
     :duration-ms duration
     :successes successes
     :errors errors}))

;;; =============================================================================
;;; REPL Compliance
;;; =============================================================================

(defn- checkpoint-compliance
  "Get REPL compliance for a checkpoint using its session IDs from meta files."
  [metas _project-path]
  (let [session-ids (->> metas (map :session-id) (filter some?))]
    (when (seq session-ids)
      (try
        (let [all-tool-calls (->> session-ids
                                  (mapcat #(analytics/extract-tool-calls %))
                                  vec)]
          (when (seq all-tool-calls)
            (analytics/score-repl-compliance all-tool-calls)))
        (catch Exception _ nil)))))

(defn- overall-compliance
  "Calculate overall REPL compliance from per-checkpoint compliance maps."
  [per-checkpoint]
  (let [scores (->> (vals per-checkpoint) (map :score) (filter some?))
        score-vals {:excellent 3 :good 2 :fair 1 :poor 0}
        avg (when (seq scores)
              (/ (reduce + (map score-vals scores)) (count scores)))]
    (cond
      (nil? avg) :unknown
      (>= avg 2.5) :excellent
      (>= avg 1.5) :good
      (>= avg 0.5) :fair
      :else :poor)))

;;; =============================================================================
;;; gather-review-data
;;; =============================================================================

(defn gather-review-data
  "Collect all review artifacts from a completed Lisa Loop.
   Returns a data map suitable for formatting or further analysis.

   Arguments:
   - project-path: path to the project root
   - loop-result: the result map from run-loop! (or run-loop-parallel!)
     Expected keys: :status, :iterations, :total-cost, :total-input-tokens, :total-output-tokens"
  [project-path loop-result]
  (let [plan (plan-edn/read-plan project-path)
        log-dir (str (fs/path project-path ".forj/logs/lisa"))

        ;; Meta files
        meta-files (read-meta-files project-path log-dir)
        metas-by-cp (meta-files-by-checkpoint meta-files)

        ;; Git data
        lisa-commits (get-lisa-commits project-path)
        commits-by-cp (group-by :checkpoint-id lisa-commits)
        git-stat (get-diff-stat-between project-path lisa-commits)

        ;; Signs from plan
        signs (:signs plan [])

        ;; Per-checkpoint data
        checkpoints (when plan
                      (mapv (fn [cp]
                              (let [cp-id (:id cp)
                                    cp-metas (get metas-by-cp cp-id [])
                                    meta-summary (checkpoint-meta-summary cp-metas)
                                    cp-commits (get commits-by-cp cp-id [])
                                    commit (first cp-commits)
                                    per-commit-diff (when (:sha commit)
                                                      (get-per-commit-diff project-path (:sha commit)))
                                    cp-signs (filterv #(= cp-id (:checkpoint %)) signs)]
                                (merge
                                 (select-keys cp [:id :description :status :type])
                                 {:started (:started meta-summary)
                                  :completed (:completed meta-summary)
                                  :duration-ms (:duration-ms meta-summary)
                                  :iterations (:iterations meta-summary)
                                  :signs cp-signs
                                  :files-changed (:files per-commit-diff)
                                  :stat-line (:stat-line per-commit-diff)
                                  :commit-message (:message commit)
                                  :commit-sha (:sha commit)})))
                            (:checkpoints plan)))

        ;; REPL compliance (best-effort, may not have session data)
        per-cp-compliance (into {}
                                (for [[cp-id metas] metas-by-cp
                                      :when cp-id
                                      :let [c (checkpoint-compliance metas project-path)]
                                      :when c]
                                  [cp-id c]))

        ;; Loop-level duration from meta files
        all-started (->> meta-files (map :started-at) (filter some?) sort first)
        all-completed (->> meta-files (map :completed-at) (filter some?) sort last)
        total-duration (calculate-duration-ms all-started all-completed)]

    {:plan {:title (:title plan)
            :status (if (plan-edn/all-complete? plan) :complete (:status plan))
            :checkpoint-count (count (:checkpoints plan))}
     :summary {:total-iterations (or (:iterations loop-result) 0)
               :total-cost (or (:total-cost loop-result) 0.0)
               :total-input-tokens (or (:total-input-tokens loop-result) 0)
               :total-output-tokens (or (:total-output-tokens loop-result) 0)
               :duration-ms total-duration}
     :checkpoints (or checkpoints [])
     :signs signs
     :git (merge {:commits (or lisa-commits [])}
                 (dissoc git-stat :diff-stat)
                 (when (:diff-stat git-stat)
                   {:diff-stat (:diff-stat git-stat)}))
     :repl-compliance {:overall (overall-compliance per-cp-compliance)
                       :per-checkpoint per-cp-compliance}}))

;;; =============================================================================
;;; format-review
;;; =============================================================================

(defn- format-header [review-data]
  (let [{:keys [plan summary]} review-data
        {:keys [title status checkpoint-count]} plan
        {:keys [total-iterations total-cost total-input-tokens total-output-tokens duration-ms]} summary]
    (str "+---------------------------------------------------------\n"
         "| Lisa Loop Review\n"
         "+---------------------------------------------------------\n"
         "| Plan: \"" title "\"\n"
         "| Status: " (name status)
         " | " checkpoint-count " checkpoints"
         " | " total-iterations " iterations"
         " | $" (format "%.2f" (double total-cost)) "\n"
         (when duration-ms
           (str "| Duration: " (format-duration duration-ms) "\n"))
         "| Tokens: " (format-tokens total-input-tokens) " in / "
         (format-tokens total-output-tokens) " out\n")))

(defn- format-checkpoint-line [cp]
  (let [{:keys [id status type iterations duration-ms
                commit-sha commit-message signs files-changed stat-line]} cp
        status-icon (case status :done "v" :failed "x" :skipped "-" " ")
        review-tag (when (= :review type) " [REVIEW]")
        iter-info (when iterations (str iterations " iter"))
        dur-info (format-duration duration-ms)
        sha-info (when commit-sha (str "[" commit-sha "]"))
        meta-parts (filterv some? [iter-info dur-info sha-info])
        meta-str (when (seq meta-parts) (str " (" (str/join ", " meta-parts) ")"))]
    (str "| " status-icon " " (name id) review-tag (or meta-str "") "\n"
         (when (seq files-changed)
           (str "|   Files: " (str/join ", " (take 5 files-changed))
                (when (> (count files-changed) 5) "...")
                (when stat-line (str " " stat-line))
                "\n"))
         (when commit-message
           (str "|   Commit: " commit-message "\n"))
         (when (seq signs)
           (str/join ""
                     (map (fn [s]
                            (str "|   ! Sign: \"" (:issue s) "\"\n"))
                          signs))))))

(defn- format-checkpoints [review-data]
  (let [checkpoints (:checkpoints review-data)]
    (when (seq checkpoints)
      (str "+---------------------------------------------------------\n"
           "| Per-Checkpoint Summary\n"
           "+---------------------------------------------------------\n"
           (str/join "|\n"
                     (map format-checkpoint-line checkpoints))))))

(defn- format-git-section [review-data]
  (let [{:keys [git]} review-data
        {:keys [commits files-changed insertions deletions]} git]
    (when (seq commits)
      (str "+---------------------------------------------------------\n"
           "| Code Changes"
           (when (or files-changed insertions deletions)
             (str " (" (or files-changed 0) " files, +"
                  (or insertions 0) "/-" (or deletions 0) ")"))
           "\n"
           "+---------------------------------------------------------\n"
           (str/join ""
                     (map (fn [{:keys [sha message]}]
                            (str "| " sha " " message "\n"))
                          commits))))))

(defn- format-compliance [review-data]
  (let [overall (get-in review-data [:repl-compliance :overall])]
    (when (and overall (not= :unknown overall))
      (str "+---------------------------------------------------------\n"
           "| REPL Compliance: " (str/capitalize (name overall)) "\n"))))

(defn- format-signs [review-data]
  (let [signs (:signs review-data)]
    (when (seq signs)
      (str "+---------------------------------------------------------\n"
           "| Signs: " (count signs) " total\n"
           "+---------------------------------------------------------\n"
           (str/join ""
                     (map (fn [s]
                            (str "|   iter " (:iteration s) ": " (:issue s) "\n"))
                          signs))))))

(defn format-review
  "Format review data as a terminal-printable string."
  [review-data]
  (str (format-header review-data)
       (format-checkpoints review-data)
       (format-git-section review-data)
       (format-compliance review-data)
       (format-signs review-data)
       "+---------------------------------------------------------\n"))

(comment
  ;; Smoke test with current project
  (gather-review-data "." {:status :complete :iterations 5 :total-cost 0.47
                           :total-input-tokens 4500000 :total-output-tokens 20000})

  ;; Format it
  (println (format-review
            (gather-review-data "." {:status :complete :iterations 5 :total-cost 0.47
                                     :total-input-tokens 4500000 :total-output-tokens 20000})))
  )
