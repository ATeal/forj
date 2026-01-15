(ns {{namespace}}.core
  (:require [ring.adapter.jetty :as jetty]
            [{{namespace}}.routes :as routes])
  (:gen-class))

(defonce ^:private server (atom nil))

(defn start-server
  "Start the HTTP server."
  [& [{:keys [port] :or {port 3000}}]]
  (when @server
    (.stop @server))
  (reset! server
          (jetty/run-jetty #'routes/app
                           {:port port :join? false}))
  (println (str "Server running on http://localhost:" port)))

(defn stop-server
  "Stop the HTTP server."
  []
  (when @server
    (.stop @server)
    (reset! server nil)
    (println "Server stopped")))

(defn -main
  "Application entry point."
  [& args]
  (let [port (if (seq args) (parse-long (first args)) 3000)]
    (start-server {:port port})
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. stop-server))))

(comment
  ;; REPL-driven development
  (start-server {:port 3000})
  ;; Server running on http://localhost:3000

  (stop-server)
  ;; Server stopped

  ;; Test endpoints
  (require '[clj-http.client :as http])
  (http/get "http://localhost:3000/api/health")

  )
