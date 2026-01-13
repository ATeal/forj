(ns forj.mcp.protocol
  "JSON-RPC 2.0 protocol handling for MCP over stdio."
  (:require [cheshire.core :as json]))

(def jsonrpc-version "2.0")

(defn read-message
  "Read a JSON-RPC message from stdin.
   Returns parsed message or nil on EOF."
  []
  (when-let [line (read-line)]
    (when (seq line)
      (try
        (json/parse-string line true)
        (catch Exception e
          {:error {:code -32700
                   :message "Parse error"
                   :data (str e)}})))))

(defn write-message
  "Write a JSON-RPC message to stdout."
  [msg]
  (println (json/generate-string msg))
  (flush))

(defn success-response
  "Create a success response for a request."
  [id result]
  {:jsonrpc jsonrpc-version
   :id id
   :result result})

(defn error-response
  "Create an error response for a request."
  [id code message & [data]]
  {:jsonrpc jsonrpc-version
   :id id
   :error (cond-> {:code code :message message}
            data (assoc :data data))})

(defn notification
  "Create a notification (no response expected)."
  [method params]
  {:jsonrpc jsonrpc-version
   :method method
   :params params})

;; Standard JSON-RPC error codes
(def error-codes
  {:parse-error      -32700
   :invalid-request  -32600
   :method-not-found -32601
   :invalid-params   -32602
   :internal-error   -32603})
