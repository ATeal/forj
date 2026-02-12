(ns forj.hooks.task-completed
  "TaskCompleted hook for Agent Teams integration.

   When a teammate marks a task complete, this hook:
   1. Maps the task subject back to a LISA_PLAN.edn checkpoint
   2. Runs checkpoint gates (REPL validation) if defined
   3. On pass: marks checkpoint done in LISA_PLAN.edn, allows completion (exit 0)
   4. On fail: rejects completion with feedback (exit 2, stderr = feedback)

   If no LISA_PLAN.edn exists or task doesn't match a checkpoint, passes through (exit 0)."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [forj.hooks.util :as util]
            [forj.lisa.agent-teams :as agent-teams]
            [forj.lisa.plan-edn :as plan-edn]
            [forj.lisa.validation :as validation]
            [forj.logging :as log]))

(defn- file-path->namespace
  "Convert a file path to a namespace name.
   e.g., 'src/myapp/core.clj' -> 'myapp.core'"
  [path]
  (-> path
      (str/replace #"^.*/src/" "")
      (str/replace #"^src/" "")
      (str/replace #"\.(clj[cs]?)$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")))

(defn- reload-checkpoint-namespace!
  "Reload the checkpoint's file namespace in the REPL before gate evaluation.
   Ensures gates validate against current disk state, not stale REPL cache."
  [checkpoint port]
  (when-let [file (:file checkpoint)]
    (let [ns-name (file-path->namespace file)]
      (log/info "task-completed" "Reloading namespace before gate eval" {:ns ns-name :port port})
      (try
        (p/shell {:out :string :err :string :continue true}
                 "clj-nrepl-eval" "-p" (str port) "-t" "5000"
                 (str "(require '" ns-name " :reload)"))
        (catch Exception e
          (log/warn "task-completed" "Failed to reload namespace" {:ns ns-name :error (.getMessage e)}))))))

(defn -main
  "Entry point for TaskCompleted hook.

   Reads task info from stdin JSON. If the task corresponds to a
   LISA_PLAN.edn checkpoint, validates gates and syncs completion."
  [& _args]
  (let [input (try (slurp *in*) (catch Exception _ ""))
        data (try (json/parse-string input true) (catch Exception _ {}))
        task-subject (:task_subject data)
        cwd (or (:cwd data) (System/getProperty "user.dir"))]

    (log/info "task-completed" "Hook invoked" {:task-subject task-subject :cwd cwd})

    ;; Check if LISA_PLAN.edn exists
    (if-not (plan-edn/plan-exists? cwd)
      (do
        (log/debug "task-completed" "No LISA_PLAN.edn found, passing through")
        (System/exit 0))

      ;; Try to find matching checkpoint
      (if-let [cp-id (agent-teams/find-checkpoint-for-task cwd task-subject)]
        (let [plan (plan-edn/read-plan cwd)
              checkpoint (plan-edn/checkpoint-by-id plan cp-id)
              gates (when checkpoint
                      (let [g (:gates checkpoint)]
                        (cond
                          (string? g) g
                          (sequential? g) (str/join " | " g)
                          :else nil)))]

          (log/info "task-completed" "Matched checkpoint" {:checkpoint cp-id :has-gates (some? gates)})

          (if (or (nil? gates) (str/blank? gates))
            ;; No gates — mark done and allow
            (do
              (log/info "task-completed" "No gates, marking checkpoint done" {:checkpoint cp-id})
              (plan-edn/mark-checkpoint-done! cwd cp-id)
              (System/exit 0))

            ;; Has gates — validate them (discover REPL port for evaluation)
            (let [port (util/discover-nrepl-port cwd)
                  _ (log/info "task-completed" "Discovered port for gate validation" {:port port})
                  ;; Reload namespace from disk to avoid false positives from stale REPL state
                  _ (when port (reload-checkpoint-namespace! checkpoint port))
                  result (validation/checkpoint-gates-passed? gates {:project-path cwd :port port})]
              (if (:passed result)
                (do
                  (log/info "task-completed" "Gates passed, marking done" {:checkpoint cp-id})
                  (plan-edn/mark-checkpoint-done! cwd cp-id)
                  (System/exit 0))
                (do
                  (log/warn "task-completed" "Gates failed, rejecting" {:checkpoint cp-id :message (:message result)})
                  (binding [*out* *err*]
                    (println (str "Gate validation failed for checkpoint " (name cp-id) ":\n"
                                  (:message result) "\n\n"
                                  "Please fix the issues and try again. "
                                  "Use reload_namespace to pick up changes, then repl_eval to verify.")))
                  (System/exit 2))))))

        ;; No matching checkpoint — pass through
        (do
          (log/debug "task-completed" "Task doesn't match a checkpoint, passing through" {:task-subject task-subject})
          (System/exit 0))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
