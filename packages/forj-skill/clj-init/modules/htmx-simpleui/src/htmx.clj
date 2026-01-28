(ns {{namespace}}.htmx
  "HTMX route handlers using SimpleUI"
  (:require [{{namespace}}.views :as views]
            [simpleui.core :as ui]))

;; In-memory state (replace with database in real app)
(defonce !counter (atom 0))

;; Page routes (return full HTML)
(defn home-handler [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (views/home-page)})

;; HTMX fragment routes (return partial HTML)
(defn increment-handler [_request]
  (let [new-count (swap! !counter inc)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str new-count)}))

;; Route definitions for reitit
(def htmx-routes
  [["/" {:get home-handler}]
   ["/api/increment" {:post increment-handler}]])

(comment
  ;; REPL experiments
  @!counter
  (reset! !counter 0)
  (increment-handler {})
  ,)
