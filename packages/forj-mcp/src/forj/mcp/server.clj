(ns forj.mcp.server
  "MCP server for Clojure REPL connectivity.

   Implements the Model Context Protocol over stdio, providing tools
   for REPL evaluation, port discovery, and project analysis."
  (:require [forj.mcp.protocol :as proto]
            [forj.mcp.tools :as tools]
            [forj.logging :as log]
            [cheshire.core :as json]))

(def server-info
  {:name "forj-mcp"
   :version "0.1.0"})

(def server-capabilities
  {:tools {}})

(defn handle-initialize
  "Handle the initialize request."
  [id _params]
  (proto/success-response id
                          {:protocolVersion "2024-11-05"
                           :serverInfo server-info
                           :capabilities server-capabilities}))

(defn handle-tools-list
  "Handle tools/list request."
  [id]
  (proto/success-response id
                          {:tools tools/tools}))

(defn handle-tools-call
  "Handle tools/call request."
  [id {:keys [name arguments]}]
  (log/debug "mcp-server" "Tool call" {:tool name})
  (let [result (tools/call-tool {:name name :arguments arguments})]
    (when-not (:success result)
      (log/warn "mcp-server" "Tool call failed" {:tool name :error (:error result)}))
    (proto/success-response id
                            {:content [{:type "text"
                                        :text (if (:success result)
                                                (or (:value result) (:ports result)
                                                    (json/generate-string result {:pretty true}))
                                                (str "Error: " (:error result)))}]
                             :isError (not (:success result))})))

(defn handle-request
  "Dispatch incoming request to appropriate handler."
  [{:keys [id method params]}]
  (case method
    "initialize" (handle-initialize id params)
    "notifications/initialized" nil  ; No response for notifications
    "tools/list" (handle-tools-list id)
    "tools/call" (handle-tools-call id params)
    "ping" (proto/success-response id {})
    ;; Unknown method
    (when id  ; Only respond if it's a request (has id)
      (proto/error-response id
                            (:method-not-found proto/error-codes)
                            (str "Method not found: " method)))))

(defn run-server
  "Main server loop. Reads JSON-RPC messages from stdin, dispatches handlers,
   writes responses to stdout."
  []
  (log/info "mcp-server" "Server starting")
  (try
    (loop []
      (when-let [msg (proto/read-message)]
        (try
          (when-let [response (handle-request msg)]
            (proto/write-message response))
          (catch Exception e
            (log/exception "mcp-server" "Error handling request" e)))
        (recur)))
    (finally
      (log/info "mcp-server" "Server stopping"))))

(defn -main
  "Entry point for MCP server."
  [& _args]
  (run-server))

;; Allow running directly with bb
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
