(ns alligator.methods.execute-command
  (:require
   [clojure.core.async :as async]
   [taoensso.timbre :refer [warn]]
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]))

;; Keep a list of commands that server can run
;; Structure: {:server1 [...] server2 [...] ...}
(defonce server-commands-map (atom {}))

(defn ^:private error-response
  [id data]
  {:jsonrpc "2.0"
   :id id
   :error {:code -32000
           :message "Could not find the right server to relay the workspace/executeCommand request"
           :data data}})

(defmethod methods/process-client-message "workspace/executeCommand"
  [_ in-chan]
  (async/go-loop []
    (when-let [message (async/<! in-chan)]
      (let [params (:params message)
            command-to-run (:command params)
            ;; all server that supports one command
            servers (->> @server-commands-map
                         (filter (fn [[_k v]] (some #{command-to-run} v)))
                         (map first))
            n-servers (count servers)]
        (cond
          (= n-servers 0) (do
                            (async/>! mux/server-output (error-response (:id message) params))
                            (warn (format "[Router] No server is capable of running %s" command-to-run)))
          (= n-servers 1) (let [server (some #(when (= (:name %) (first servers)) %) @mux/enabled-servers)]
                            (async/>! (:stdin server) message))
          :else (do
                  (async/>! mux/server-output (error-response (:id message) params))
                  (warn (format "[Router] More than 1 server is capable of running %s" command-to-run))))))
    (recur)))
