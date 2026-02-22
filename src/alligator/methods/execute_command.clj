(ns alligator.methods.execute-command
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]
   [taoensso.timbre :refer [warn]]))

(defn ^:private error-response
  [id data]
  {:jsonrpc "2.0"
   :id id
   :error {:code -32000
           :message "Could not find the right server to relay the workspace/executeCommand request"
           :data data}})

(defmethod methods/process-client-message "workspace/executeCommand"
  [_ in-chan multiplexer]
  (async/go-loop []
    (when-let [message (async/<! in-chan)]
      (let [params (:params message)
            command-to-run (:command params)
            servers (mux/get-servers-for-command multiplexer command-to-run)
            n-servers (count servers)]
        (cond
          (= n-servers 0) (do
                            (async/>! (mux/get-output-chan multiplexer) (error-response (:id message) params))
                            (warn (format "[Router] No server is capable of running %s" command-to-run)))
          (= n-servers 1) (let [server (mux/get-server-by-name multiplexer (first servers))]
                            (async/>! (:stdin server) message))
          :else (do
                  (async/>! (mux/get-output-chan multiplexer) (error-response (:id message) params))
                  (warn (format "[Router] More than 1 server is capable of running %s" command-to-run)))))
      (recur))))
