(ns {{namespace}}.routes
  (:require [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [{{namespace}}.handlers :as handlers]))

(def app
  (ring/ring-handler
   (ring/router
    [["/api"
      ["/health" {:get handlers/health}]
      ["/echo" {:post handlers/echo}]]]
    {:data {:coercion reitit.coercion.spec/coercion
            :muuntaja m/instance
            :middleware [muuntaja/format-middleware
                         coercion/coerce-exceptions-middleware
                         coercion/coerce-request-middleware
                         coercion/coerce-response-middleware]}})
   (ring/routes
    (ring/create-default-handler))))
