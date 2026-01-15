(ns {{namespace}}.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]))

;; Database connection (SQLite file in project root)
(def db-spec
  {:dbtype "sqlite"
   :dbname "{{project-name}}.db"})

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
  ;; Create a test table
  (jdbc/execute! (datasource)
                 ["CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)"])

  ;; Insert and query
  (execute! {:insert-into :users :values [{:name "Alice"}]})
  (execute! {:select [:*] :from [:users]})
  )
