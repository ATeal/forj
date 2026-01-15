(ns {{namespace}}.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]))

;; Database connection
(def db-spec
  {:dbtype "postgresql"
   :dbname "{{project-name}}"
   :host "localhost"
   :port 5432
   :user "postgres"
   :password "postgres"})

(def ^:dynamic *ds*
  "Dynamic var for database connection."
  nil)

(defn datasource []
  (or *ds* (jdbc/get-datasource db-spec)))

(defn execute!
  "Execute a HoneySQL query."
  [query]
  (jdbc/execute! (datasource)
                 (sql/format query)
                 {:builder-fn rs/as-unqualified-maps}))

(defn execute-one!
  "Execute a HoneySQL query, return first result."
  [query]
  (jdbc/execute-one! (datasource)
                     (sql/format query)
                     {:builder-fn rs/as-unqualified-maps}))

(comment
  ;; REPL exploration
  (execute! {:select [:*] :from [:users] :limit 10})
  (execute-one! {:select [[[:count :*] :count]] :from [:users]})
  )
