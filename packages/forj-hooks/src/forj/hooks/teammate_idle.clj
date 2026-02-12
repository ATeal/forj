(ns forj.hooks.teammate-idle
  "TeammateIdle hook for Agent Teams integration.

   When a teammate is about to go idle, this hook checks if there are
   more ready checkpoints in LISA_PLAN.edn. If so, it redirects the
   teammate to the next task (exit 2, stderr = suggestion).

   If no LISA_PLAN.edn exists or no ready checkpoints remain, lets
   the teammate go idle (exit 0)."
  (:require [cheshire.core :as json]
            [forj.lisa.agent-teams :as agent-teams]
            [forj.lisa.plan-edn :as plan-edn]
            [forj.logging :as log]))

(defn -main
  "Entry point for TeammateIdle hook.

   Reads teammate info from stdin JSON. If ready checkpoints exist,
   suggests the next one to keep the teammate working."
  [& _args]
  (let [input (try (slurp *in*) (catch Exception _ ""))
        data (try (json/parse-string input true) (catch Exception _ {}))
        cwd (or (:cwd data) (System/getProperty "user.dir"))]

    (log/info "teammate-idle" "Hook invoked" {:cwd cwd :teammate (:teammate_name data)})

    ;; Check if LISA_PLAN.edn exists
    (if-not (plan-edn/plan-exists? cwd)
      (do
        (log/debug "teammate-idle" "No LISA_PLAN.edn found, letting teammate idle")
        (System/exit 0))

      (let [plan (plan-edn/read-plan cwd)
            ready (plan-edn/ready-checkpoints plan)]

        (if (seq ready)
          ;; Suggest next ready checkpoint
          (let [next-cp (first ready)
                task-desc (agent-teams/checkpoint->task-description
                           next-cp {:plan-title (:title plan)})]
            (log/info "teammate-idle" "Redirecting to next checkpoint"
                      {:checkpoint (:id next-cp) :teammate (:teammate_name data)})
            (binding [*out* *err*]
              (println (str "Next ready checkpoint: " (name (:id next-cp))
                            "\n\n" (:description task-desc))))
            (System/exit 2))

          ;; No ready checkpoints — let teammate idle
          (do
            (log/info "teammate-idle" "No ready checkpoints, letting teammate idle")
            (System/exit 0)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
