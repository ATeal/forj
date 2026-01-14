(ns forj.hooks.loop-state
  "State management for Lisa Loop autonomous development loops."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(def state-file
  (str (fs/path (System/getProperty "user.home") ".forj" "loop-state.edn")))

(defn read-state
  "Read current loop state. Returns nil if no active loop."
  []
  (when (fs/exists? state-file)
    (try
      (edn/read-string (slurp state-file))
      (catch Exception _ nil))))

(defn write-state!
  "Write loop state to file."
  [state]
  (fs/create-dirs (fs/parent state-file))
  (spit state-file (pr-str state))
  state)

(defn start-loop!
  "Initialize a new loop with the given configuration."
  [{:keys [prompt max-iterations completion-promise]
    :or {max-iterations 30
         completion-promise "COMPLETE"}}]
  (write-state!
   {:active? true
    :iteration 0
    :max-iterations max-iterations
    :prompt prompt
    :completion-promise completion-promise
    :started-at (str (java.time.Instant/now))
    :validation-history []}))

(defn increment-iteration!
  "Increment the iteration counter and optionally store validation results."
  [state & [validation-results]]
  (let [new-state (-> state
                      (update :iteration inc)
                      (update :validation-history conj
                              {:iteration (inc (:iteration state))
                               :at (str (java.time.Instant/now))
                               :results validation-results}))]
    (write-state! new-state)))

(defn clear-state!
  "Clear the loop state (end the loop)."
  []
  (when (fs/exists? state-file)
    (fs/delete state-file))
  nil)

(defn active?
  "Check if a loop is currently active."
  []
  (boolean (:active? (read-state))))

(comment
  ;; Test expressions
  (read-state)
  ;; => nil (when no loop active)

  (start-loop! {:prompt "Build a REST API"
                :max-iterations 20
                :completion-promise "DONE"})
  ;; => {:active? true, :iteration 0, ...}

  (read-state)
  ;; => {:active? true, ...}

  (increment-iteration! (read-state) {:all-passed true})
  ;; => {:active? true, :iteration 1, ...}

  (active?)
  ;; => true

  (clear-state!)
  ;; => nil

  (active?)
  ;; => false
  )
