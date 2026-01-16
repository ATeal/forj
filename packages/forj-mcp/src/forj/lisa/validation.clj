(ns forj.lisa.validation
  "Pluggable validation methods for Lisa Loop checkpoints.

   Supports multiple validation types:
   - :repl - Evaluate Clojure expressions in the REPL
   - :chrome - Use Chrome MCP/Playwright for UI validation (screenshots, assertions)
   - :judge - Use LLM-as-judge for subjective criteria (aesthetics, clarity)

   Validation config in LISA_PLAN.md:

   ### 1. [PENDING] Create login form
   - Validation: repl:(render-to-string [login-form]) returns hiccup | chrome:screenshot /login | judge:Form has clean layout"
  (:require [babashka.process :as p]
            [clojure.string :as str]))

;; =============================================================================
;; Validation Parsing
;; =============================================================================

(defn- parse-validation-item
  "Parse a single validation item like 'repl:(verify-password \"test\" hash) => true'"
  [item]
  (let [item (str/trim item)]
    (cond
      ;; REPL validation: "repl:(some-fn arg) => expected"
      (str/starts-with? item "repl:")
      {:type :repl
       :expression (subs item 5)
       :raw item}

      ;; Chrome validation: "chrome:screenshot /login" or "chrome:click #submit"
      (str/starts-with? item "chrome:")
      (let [rest (str/trim (subs item 7))
            [action & args] (str/split rest #"\s+" 2)]
        {:type :chrome
         :action (keyword action)
         :args (first args)
         :raw item})

      ;; LLM-as-judge: "judge:Does this look professional?"
      (str/starts-with? item "judge:")
      {:type :judge
       :criteria (subs item 6)
       :raw item}

      ;; Default to REPL if no prefix
      :else
      {:type :repl
       :expression item
       :raw item})))

(defn parse-validation-string
  "Parse a validation string into structured items.
   Items are separated by | or newlines."
  [validation-str]
  (when (and validation-str (not (str/blank? validation-str)))
    (->> (str/split validation-str #"\s*\|\s*|\n")
         (map str/trim)
         (remove str/blank?)
         (mapv parse-validation-item))))

;; =============================================================================
;; REPL Validation
;; =============================================================================

(defn- eval-repl-validation
  "Run a REPL validation using clj-nrepl-eval."
  [{:keys [expression]} port]
  (try
    (let [;; Parse expected result if present (e.g., "(foo) => 42")
          [expr expected] (if-let [[_ e exp] (re-matches #"(.+?)\s*=>\s*(.+)" expression)]
                            [e exp]
                            [expression nil])
          result (p/shell {:out :string :err :string :continue true}
                          "clj-nrepl-eval" "-p" (str port) "-t" "5000" expr)]
      (if (zero? (:exit result))
        (let [actual (str/trim (:out result))
              passed? (or (nil? expected)
                          (= (str/trim expected) actual))]
          {:passed passed?
           :actual actual
           :expected expected
           :type :repl})
        {:passed false
         :error (or (not-empty (:err result)) "Evaluation failed")
         :type :repl}))
    (catch Exception e
      {:passed false
       :error (.getMessage e)
       :type :repl})))

;; =============================================================================
;; Chrome/Playwright Validation (Stub)
;; =============================================================================

(defn- eval-chrome-validation
  "Run a Chrome MCP validation.
   Note: This is a stub - actual implementation would use Chrome MCP tools."
  [{:keys [action args]}]
  ;; Stub implementation - returns instructions for manual/MCP execution
  {:passed :pending
   :type :chrome
   :action action
   :args args
   :note (str "Chrome validation requires MCP: " (name action) " " args)})

;; =============================================================================
;; LLM-as-Judge Validation (Stub)
;; =============================================================================

(defn- eval-judge-validation
  "Run an LLM-as-judge validation.
   Note: This is a stub - actual implementation would call Claude API."
  [{:keys [criteria]}]
  ;; Stub implementation - returns instructions for manual/LLM execution
  {:passed :pending
   :type :judge
   :criteria criteria
   :note (str "Judge validation requires LLM evaluation: " criteria)})

;; =============================================================================
;; Main Validation Runner
;; =============================================================================

(defn run-validation
  "Run a single validation item.

   Options:
   - :port - nREPL port for REPL validations
   - :chrome-tab - Chrome tab ID for Chrome validations (future)"
  [validation-item {:keys [port]}]
  (case (:type validation-item)
    :repl (eval-repl-validation validation-item port)
    :chrome (eval-chrome-validation validation-item)
    :judge (eval-judge-validation validation-item)
    {:passed false :error (str "Unknown validation type: " (:type validation-item))}))

(defn run-validations
  "Run all validations for a checkpoint.
   Returns {:all-passed bool :results [...] :summary string}"
  [validation-str {:keys [port] :as opts}]
  (let [validations (parse-validation-string validation-str)
        results (mapv #(assoc (run-validation % opts) :raw (:raw %)) validations)
        passed (filter #(true? (:passed %)) results)
        failed (filter #(false? (:passed %)) results)
        pending (filter #(= :pending (:passed %)) results)]
    {:all-passed (and (empty? failed) (empty? pending))
     :passed-count (count passed)
     :failed-count (count failed)
     :pending-count (count pending)
     :results results
     :summary (cond
                (empty? validations) "No validations defined"
                (seq failed) (str "FAILED: " (count failed) " of " (count validations))
                (seq pending) (str "PENDING: " (count pending) " require manual/MCP execution")
                :else (str "PASSED: " (count passed) " of " (count validations)))}))

(defn checkpoint-gates-passed?
  "Check if all gates for a checkpoint have passed.
   Gates are a stricter form of validation that MUST pass before advancing."
  [gates-str {:keys [port] :as opts}]
  (if (or (nil? gates-str) (str/blank? gates-str))
    {:passed true :message "No gates defined"}
    (let [{:keys [all-passed summary]} (run-validations gates-str opts)]
      {:passed all-passed
       :message summary})))

(comment
  ;; Test expressions

  ;; Parse validation string
  (parse-validation-string "repl:(+ 1 2) => 3 | judge:Looks good")
  ;; => [{:type :repl :expression "(+ 1 2) => 3"}
  ;;     {:type :judge :criteria "Looks good"}]

  ;; Run REPL validation (requires running nREPL)
  (run-validation {:type :repl :expression "(+ 1 2) => 3"} {:port 1667})

  ;; Run all validations
  (run-validations "repl:(+ 1 2) => 3" {:port 1667})
  )
