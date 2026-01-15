(ns {{namespace}}.handlers)

(defn health
  "Health check endpoint."
  [_request]
  {:status 200
   :body {:status "ok"
          :timestamp (System/currentTimeMillis)}})

(defn echo
  "Echo back the request body."
  [request]
  {:status 200
   :body {:echo (:body-params request)}})

(comment
  ;; Test handlers in REPL

  (health {})
  ;; => {:status 200, :body {:status "ok", :timestamp ...}}

  (echo {:body-params {:message "Hello!"}})
  ;; => {:status 200, :body {:echo {:message "Hello!"}}}

  )
