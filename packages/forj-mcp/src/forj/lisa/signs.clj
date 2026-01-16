(ns forj.lisa.signs
  "Lisa Loop signs (guardrails) - records failures and learnings for future iterations.

   Signs are stored in LISA_SIGNS.md in the project root.
   Format:

   # Lisa Loop Signs (Learnings)

   ## Sign 1 (Iteration 3, 2026-01-16T10:30:00Z)
   **Checkpoint:** 2 - Create JWT module
   **Issue:** Forgot to require clojure.string in namespace
   **Fix:** Always check requires when adding string functions
   **Severity:** error

   ## Sign 2 (Iteration 5, 2026-01-16T10:35:00Z)
   **Checkpoint:** 3 - Auth middleware
   **Issue:** REPL connection failed - port not found
   **Fix:** Check nREPL is running with discover_repls before validation
   **Severity:** warning"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def signs-filename "LISA_SIGNS.md")

(defn signs-path
  "Get the signs file path for a project."
  [project-path]
  (str (fs/path project-path signs-filename)))

(defn signs-exist?
  "Check if a signs file exists."
  [project-path]
  (fs/exists? (signs-path project-path)))

;;; Parsing

(defn- parse-sign-block
  "Parse a sign block from lines starting with ## Sign N"
  [lines]
  (when (seq lines)
    (let [header (first lines)
          ;; Parse "## Sign 1 (Iteration 3, 2026-01-16T10:30:00Z)"
          [_ num iter-str timestamp] (re-find #"##\s+Sign\s+(\d+)\s+\(Iteration\s+(\d+),\s+([^)]+)\)" header)
          detail-lines (rest lines)
          parse-field (fn [prefix]
                        (some->> detail-lines
                                 (filter #(str/starts-with? % prefix))
                                 first
                                 (re-find (re-pattern (str (java.util.regex.Pattern/quote prefix) "\\s*(.*)")))
                                 second
                                 str/trim))]
      (when num
        {:number (parse-long num)
         :iteration (when iter-str (parse-long iter-str))
         :timestamp timestamp
         :checkpoint (parse-field "**Checkpoint:**")
         :issue (parse-field "**Issue:**")
         :fix (parse-field "**Fix:**")
         :severity (keyword (or (parse-field "**Severity:**") "error"))}))))

(defn- split-sign-blocks
  "Split content into sign blocks."
  [lines]
  (let [sign-starts (keep-indexed (fn [i line]
                                    (when (re-matches #"##\s+Sign\s+\d+.*" line) i))
                                  lines)]
    (if (empty? sign-starts)
      []
      (let [block-bounds (concat (map vector sign-starts (rest sign-starts))
                                 [[(last sign-starts) (count lines)]])]
        (mapv (fn [[start end]]
                (subvec (vec lines) start end))
              block-bounds)))))

(defn parse-signs
  "Parse a LISA_SIGNS.md file into a list of sign maps."
  [project-path]
  (when (signs-exist? project-path)
    (let [content (slurp (signs-path project-path))
          lines (str/split-lines content)
          blocks (split-sign-blocks lines)]
      (keep parse-sign-block blocks))))

(defn read-signs
  "Read signs file content as raw string (for injection into prompts)."
  [project-path]
  (when (signs-exist? project-path)
    (slurp (signs-path project-path))))

;;; Writing

(defn- sign->markdown
  "Convert a sign map to markdown."
  [{:keys [number iteration timestamp checkpoint issue fix severity]}]
  (str/join "\n"
            [(str "## Sign " number " (Iteration " iteration ", " timestamp ")")
             (when checkpoint (str "**Checkpoint:** " checkpoint))
             (str "**Issue:** " issue)
             (str "**Fix:** " fix)
             (str "**Severity:** " (name (or severity :error)))]))

(defn append-sign!
  "Append a new sign to LISA_SIGNS.md."
  [project-path {:keys [iteration checkpoint issue fix severity]
                 :or {severity :error}}]
  (let [path (signs-path project-path)
        existing-signs (or (parse-signs project-path) [])
        next-number (inc (count existing-signs))
        timestamp (str (java.time.Instant/now))
        new-sign {:number next-number
                  :iteration iteration
                  :timestamp timestamp
                  :checkpoint checkpoint
                  :issue issue
                  :fix fix
                  :severity severity}
        markdown (sign->markdown new-sign)]
    ;; Create file with header if doesn't exist
    (when-not (signs-exist? project-path)
      (fs/create-dirs (fs/parent path))
      (spit path "# Lisa Loop Signs (Learnings)\n\nThese are learnings from previous iterations to avoid repeating mistakes.\n\n"))
    ;; Append the new sign
    (spit path (str markdown "\n\n") :append true)
    new-sign))

(defn clear-signs!
  "Delete the signs file (typically at loop completion)."
  [project-path]
  (let [path (signs-path project-path)]
    (when (fs/exists? path)
      (fs/delete path))))

(defn signs-summary
  "Get a brief summary of signs for prompt injection."
  [project-path]
  (when-let [signs (seq (parse-signs project-path))]
    (let [errors (filter #(= :error (:severity %)) signs)
          warnings (filter #(= :warning (:severity %)) signs)]
      {:total (count signs)
       :errors (count errors)
       :warnings (count warnings)
       :recent (take 5 (reverse signs))})))

(comment
  ;; Test expressions

  ;; Append a sign
  (append-sign! "/tmp/signs-test"
                {:iteration 3
                 :checkpoint "2 - Create JWT module"
                 :issue "Forgot to require clojure.string in namespace"
                 :fix "Always check requires when adding string functions"
                 :severity :error})

  ;; Parse signs
  (parse-signs "/tmp/signs-test")

  ;; Read raw content
  (read-signs "/tmp/signs-test")

  ;; Get summary
  (signs-summary "/tmp/signs-test")

  ;; Clear
  (clear-signs! "/tmp/signs-test")
  )
