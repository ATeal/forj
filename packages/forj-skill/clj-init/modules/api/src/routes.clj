(ns {{project-name}}.routes
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as parameters]
            [jsonista.core :as json]))

(defn json-response
  "Create a JSON response."
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string body)})

(defn hello-handler
  [_request]
  (json-response 200 {:message "Hello, World!"
                      :status "ok"}))

(def app
  (ring/ring-handler
   (ring/router
    [["/api"
      ["/hello" {:get hello-handler}]]]
    {:data {:middleware [parameters/parameters-middleware]}})
   (ring/routes
    (ring/redirect-trailing-slash-handler)
    (ring/create-default-handler))))

(comment
  ;; Test routes
  (app {:request-method :get :uri "/api/hello"})
  )
