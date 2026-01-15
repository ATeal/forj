(ns {{namespace}}.core
  (:require [{{namespace}}.routes :as routes]
            [ring.adapter.jetty :as jetty]))

(defonce server (atom nil))

(defn start-server
  "Start the HTTP server."
  [{:keys [port] :or {port 3000}}]
  (println (str "Starting server on http://localhost:" port))
  (reset! server
          (jetty/run-jetty routes/app
                           {:port port
                            :join? false})))

(defn stop-server
  "Stop the HTTP server."
  []
  (when-let [s @server]
    (.stop s)
    (reset! server nil)
    (println "Server stopped")))

(defn -main
  [& _args]
  (start-server {:port 3000}))

(comment
  ;; REPL workflow
  (start-server {:port 3000})
  (stop-server)

  ;; Test the routes
  (require '[{{namespace}}.routes :as routes])
  (routes/app {:request-method :get :uri "/api/hello"})
  )
