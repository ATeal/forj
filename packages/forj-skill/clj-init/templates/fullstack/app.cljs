(ns {{namespace}}.app
  (:require [reagent.dom :as rdom]
            [{{namespace}}.views :as views]))

(defn ^:dev/after-load reload
  "Called after hot reload."
  []
  (rdom/render [views/app] (.getElementById js/document "app")))

(defn init
  "Application entry point."
  []
  (println "Starting {{project-name}}...")
  (reload))
