(ns {{namespace}}.views
  (:require [hiccup2.core :as h]
            [simpleui.core :as ui]))

;; Layout wrapper
(defn layout [& content]
  (str
   (h/html
    [:html
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title "{{project-name}}"]
      ;; HTMX - loads async
      [:script {:src "https://unpkg.com/htmx.org@2.0.4"
                :defer true}]]
     [:body
      content]])))

;; Home page
(defn home-page []
  (layout
   [:div {:style "max-width: 800px; margin: 0 auto; padding: 2rem;"}
    [:h1 "Welcome to {{project-name}}"]
    [:p "HTMX + SimpleUI + Clojure"]

    ;; Example HTMX component
    [:div {:id "counter" :style "margin-top: 2rem;"}
     [:p "Count: " [:span {:id "count"} "0"]]
     [:button {:hx-post "/api/increment"
               :hx-target "#count"
               :hx-swap "innerHTML"
               :style "padding: 0.5rem 1rem;"}
      "Increment"]]]))

(comment
  ;; REPL experiments
  (home-page)
  ,)
