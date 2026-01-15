(ns {{project-name}}.views
  (:require [re-frame.core :as rf]))

(defn app []
  (let [counter @(rf/subscribe [:counter])]
    [:div {:style {:text-align "center" :padding "40px"}}
     [:h1 "{{project-name}}"]
     [:p {:style {:font-size "48px" :margin "20px 0"}}
      counter]
     [:button
      {:style {:background "#007AFF"
               :color "white"
               :border "none"
               :padding "10px 20px"
               :font-size "18px"
               :border-radius "8px"
               :cursor "pointer"}
       :on-click #(rf/dispatch [:increment])}
      "Increment"]]))

(comment
  ;; REPL exploration
  @(rf/subscribe [:counter])
  )
