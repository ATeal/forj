(ns forj.lisa.validation
  "Pluggable validation methods for Lisa Loop checkpoints.

   Supports multiple validation types:
   - :repl - Evaluate Clojure expressions in the REPL
   - :chrome - Use Chrome MCP/Playwright for UI validation (screenshots, assertions)
   - :judge - Use LLM-as-judge for subjective criteria (aesthetics, clarity)

   Validation config in LISA_PLAN.edn :gates field:

   {:id :create-login-form
    :description \"Create login form\"
    :gates [\"repl:(render-to-string [login-form]) => hiccup\"
            \"chrome:screenshot /login\"
            \"judge:Form has clean layout\"]}"
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

(defn- extract-repl-value
  "Extract just the value from REPL output.
   Input: '=> 3\n*======== user | bb ========*'
   Output: '3'"
  [output]
  (when output
    (let [lines (str/split-lines output)
          first-line (first lines)]
      (if (str/starts-with? first-line "=> ")
        (subs first-line 3)
        first-line))))

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
        (let [raw-output (str/trim (:out result))
              actual (extract-repl-value raw-output)
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
;; Chrome/Playwright Validation
;; =============================================================================

(defn- build-chrome-prompt
  "Build the prompt for Chrome/Playwright validation.

   Args format for screenshot: 'path' or 'url path' (e.g., 'http://localhost:3000 /tmp/shot.png')"
  [action args]
  (case action
    :screenshot
    (let [;; Parse args: either 'path' or 'url path'
          parts (when args (str/split (str/trim args) #"\s+" 2))
          [url-or-path path] (if (= 1 (count parts))
                               [nil (first parts)]
                               parts)
          has-url? (and url-or-path (str/starts-with? url-or-path "http"))]
      (str "Take a screenshot using Playwright MCP.\n\n"
           (when has-url?
             (str "First navigate to: " url-or-path "\n"))
           "Save screenshot to: " (or path url-or-path) "\n\n"
           "Steps:\n"
           (if has-url?
             (str "1. Use mcp__playwright__browser_navigate with url=\"" url-or-path "\"\n"
                  "2. Use mcp__playwright__browser_wait_for with time=2\n"
                  "3. Use mcp__playwright__browser_take_screenshot with filename=\"" path "\"\n"
                  "4. Respond with ONLY: SUCCESS or FAILED: <reason>")
             (str "1. Use mcp__playwright__browser_take_screenshot with filename=\"" (or path args) "\"\n"
                  "2. Respond with ONLY: SUCCESS or FAILED: <reason>\n"
                  "If the browser is not open, navigate to about:blank first, then take screenshot."))))

    :navigate
    (str "Navigate to URL: " args "\n\n"
         "Steps:\n"
         "1. Use mcp__playwright__browser_navigate with url=\"" args "\"\n"
         "2. Wait briefly for page load with mcp__playwright__browser_wait_for time=2\n"
         "3. Respond with ONLY: SUCCESS or FAILED: <reason>")

    :snapshot
    (let [url args]
      (str "Get accessibility snapshot"
           (when url (str " of " url))
           ".\n\n"
           "Steps:\n"
           (when url
             (str "1. Use mcp__playwright__browser_navigate with url=\"" url "\"\n"
                  "2. Use mcp__playwright__browser_wait_for with time=2\n"))
           (if url "3" "1") ". Use mcp__playwright__browser_snapshot\n"
           (if url "4" "2") ". If snapshot contains content, respond with: SUCCESS\n"
           (if url "5" "3") ". If empty or error, respond with: FAILED: <reason>"))

    :click
    (str "Click on element: " args "\n\n"
         "Steps:\n"
         "1. Use mcp__playwright__browser_snapshot to find the element\n"
         "2. Use mcp__playwright__browser_click with element and ref\n"
         "3. Respond with ONLY: SUCCESS or FAILED: <reason>")

    :wait
    (str "Wait for condition: " args "\n\n"
         "Steps:\n"
         "1. Use mcp__playwright__browser_wait_for with appropriate parameters\n"
         "2. Respond with ONLY: SUCCESS or FAILED: <reason>")

    ;; Default for unknown actions
    (str "Perform Chrome action: " (name action) " " args "\n"
         "Respond with ONLY: SUCCESS or FAILED: <reason>")))

(defn- parse-chrome-response
  "Parse the response from Chrome validation."
  [response]
  (let [trimmed (str/trim response)]
    (cond
      (str/starts-with? (str/upper-case trimmed) "SUCCESS")
      {:passed true
       :message (str/trim (subs trimmed (min 7 (count trimmed))))}

      (str/starts-with? (str/upper-case trimmed) "FAILED")
      {:passed false
       :reason (str/trim (subs trimmed (min 7 (count trimmed))))}

      :else
      {:passed false
       :reason (str "Unexpected response: " trimmed)})))

(defn- eval-chrome-validation
  "Run a Chrome/Playwright MCP validation by shelling out to Claude.

   Supported actions:
   - :screenshot <path> - Take screenshot and save to path
   - :navigate <url> - Navigate to URL
   - :snapshot - Get accessibility snapshot
   - :click <selector> - Click an element
   - :wait <condition> - Wait for condition"
  [{:keys [action args]}]
  (try
    (let [prompt (build-chrome-prompt action args)
          ;; Shell out to Claude with Playwright MCP
          result (p/shell {:out :string :err :string :continue true
                           :in prompt}
                          "claude" "-p"
                          "--model" "claude-3-5-haiku-latest"
                          "--output-format" "text"
                          "--max-turns" "5"
                          "--allowedTools" "mcp__playwright__*"
                          "--dangerously-skip-permissions")]
      (if (zero? (:exit result))
        (let [{:keys [passed reason message]} (parse-chrome-response (:out result))]
          {:passed passed
           :type :chrome
           :action action
           :args args
           :message (or message reason)})
        {:passed false
         :type :chrome
         :action action
         :args args
         :error (or (not-empty (:err result)) "Chrome validation failed")}))
    (catch Exception e
      {:passed false
       :type :chrome
       :action action
       :args args
       :error (.getMessage e)})))

;; =============================================================================
;; LLM-as-Judge Validation
;; =============================================================================

(defn- find-recent-screenshot
  "Find the most recent screenshot in .forj/logs/lisa/ or common screenshot locations."
  [project-path]
  (try
    (let [candidates [(str project-path "/.forj/logs/lisa/")
                      (str project-path "/")
                      "/tmp/"]
          find-latest (fn [dir]
                        (let [result (p/shell {:out :string :err :string :continue true}
                                              "find" dir "-maxdepth" "2"
                                              "-name" "*.png" "-o" "-name" "*.jpg"
                                              "-type" "f" "-mmin" "-10" "-printf" "%T@ %p\\n"
                                              "|" "sort" "-rn" "|" "head" "-1")]
                          (when (and (zero? (:exit result))
                                     (not (str/blank? (:out result))))
                            (second (str/split (str/trim (:out result)) #" " 2)))))]
      (some find-latest candidates))
    (catch Exception _
      nil)))

(defn- build-judge-prompt
  "Build the prompt for LLM-as-judge evaluation."
  [criteria screenshot-path]
  (str "You are evaluating a UI or code output against specific criteria.\n\n"
       "## Criteria\n"
       criteria "\n\n"
       (when screenshot-path
         (str "## Screenshot\n"
              "A screenshot has been provided at: " screenshot-path "\n"
              "Analyze the visual content to evaluate the criteria.\n\n"))
       "## Instructions\n"
       "Evaluate whether the criteria is met. Respond with ONLY:\n"
       "- PASS: <brief reason> - if the criteria is clearly met\n"
       "- FAIL: <brief reason> - if the criteria is not met\n"
       "- UNCLEAR: <brief reason> - if you cannot determine from available information\n\n"
       "Be strict but fair. Minor imperfections are okay if the core criteria is met."))

(defn- parse-judge-response
  "Parse the judge response to extract pass/fail/unclear and reason."
  [response]
  (let [trimmed (str/trim response)]
    (cond
      (str/starts-with? (str/upper-case trimmed) "PASS")
      {:passed true
       :reason (str/trim (subs trimmed (min 5 (count trimmed))))}

      (str/starts-with? (str/upper-case trimmed) "FAIL")
      {:passed false
       :reason (str/trim (subs trimmed (min 5 (count trimmed))))}

      (str/starts-with? (str/upper-case trimmed) "UNCLEAR")
      {:passed :unclear
       :reason (str/trim (subs trimmed (min 8 (count trimmed))))}

      :else
      {:passed :unclear
       :reason trimmed})))

(defn- eval-judge-validation
  "Run an LLM-as-judge validation by shelling out to Claude.
   Optionally uses a recent screenshot for visual evaluation."
  [{:keys [criteria]} {:keys [project-path screenshot-path]}]
  (try
    (let [;; Try to find a recent screenshot if not provided
          screenshot (or screenshot-path
                         (when project-path
                           (find-recent-screenshot project-path)))
          prompt (build-judge-prompt criteria screenshot)
          ;; Shell out to Claude with haiku for speed/cost
          result (p/shell {:out :string :err :string :continue true
                           :in prompt}
                          "claude" "-p"
                          "--model" "claude-haiku-4-20250514"
                          "--output-format" "text"
                          "--max-turns" "1"
                          "--dangerously-skip-permissions")]
      (if (zero? (:exit result))
        (let [{:keys [passed reason]} (parse-judge-response (:out result))]
          {:passed passed
           :type :judge
           :criteria criteria
           :reason reason
           :screenshot screenshot})
        {:passed false
         :type :judge
         :criteria criteria
         :error (or (not-empty (:err result)) "Claude evaluation failed")}))
    (catch Exception e
      {:passed false
       :type :judge
       :criteria criteria
       :error (.getMessage e)})))

;; =============================================================================
;; Main Validation Runner
;; =============================================================================

(defn run-validation
  "Run a single validation item.

   Options:
   - :port - nREPL port for REPL validations
   - :project-path - Project path for judge validations (screenshot discovery)
   - :screenshot-path - Explicit screenshot path for judge validations
   - :chrome-tab - Chrome tab ID for Chrome validations (future)"
  [validation-item {:keys [port _project-path _screenshot-path] :as opts}]
  (case (:type validation-item)
    :repl (eval-repl-validation validation-item port)
    :chrome (eval-chrome-validation validation-item)
    :judge (eval-judge-validation validation-item opts)
    {:passed false :error (str "Unknown validation type: " (:type validation-item))}))

(defn run-validations
  "Run all validations for a checkpoint.
   Returns {:all-passed bool :results [...] :summary string}"
  [validation-str {:keys [_port] :as opts}]
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
  [gates-str {:keys [_port] :as opts}]
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

  (parse-validation-string "chrome:screenshot /tmp/test.png")
  ;; => [{:type :chrome :action :screenshot :args "/tmp/test.png"}]

  ;; Run REPL validation (requires running nREPL)
  (run-validation {:type :repl :expression "(+ 1 2) => 3"} {:port 1669})

  ;; Run Chrome/Playwright validation (requires Playwright MCP)
  ;; Takes a screenshot and saves to specified path
  (run-validation {:type :chrome :action :screenshot :args "/tmp/lisa-screenshot.png"} {})

  ;; Navigate to URL
  (run-validation {:type :chrome :action :navigate :args "http://localhost:3000"} {})

  ;; Get accessibility snapshot
  (run-validation {:type :chrome :action :snapshot :args nil} {})

  ;; Run judge validation (calls Claude haiku)
  (run-validation {:type :judge :criteria "The UI has a clean, modern layout"}
                  {:project-path "/tmp/test-project"})

  ;; Run all validations
  (run-validations "repl:(+ 1 2) => 3 | judge:UI looks professional"
                   {:port 1669 :project-path "/tmp/test-project"})

  ;; Run Chrome screenshot validation via validation string
  (run-validations "chrome:screenshot /tmp/test.png" {})
  )
