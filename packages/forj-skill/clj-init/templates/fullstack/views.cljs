(ns {{namespace}}.views
  (:require [reagent.core :as r]))

(defonce state (r/atom {:count 0}))

(defn counter []
  [:div.counter
   [:p "Count: " (:count @state)]
   [:button {:on-click #(swap! state update :count inc)} "+"]])

(defn app []
  [:div.app
   [:h1 "{{project-name}}"]
   [counter]])
