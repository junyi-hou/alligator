(ns alligator.methods.diagnostics
  "Merge publishDiagnostics returns from the servers."
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]))

;; Cache published diagnostics from all servers.
;; Structure: {uri {server-name {:diagnostics [...] :version n}}}
(defonce diagnostics-cache (atom {}))

;; TODO:
;; 1/ we should expose version
(defn ^:private join-diagnostics [uri]
  (let [new-diagnostics (->> (get @diagnostics-cache uri)
                             vals
                             (map :diagnostics)
                             (keep identity)
                             (apply concat)
                             vec)]
    {:jsonrpc "2.0"
     :method "textDocument/publishDiagnostics"
     :params {:diagnostics new-diagnostics :uri uri}}))

(defn ^:private process-server-diagnostics
  "Process a single diagnostic message from a server and return the merged result."
  [msg]
  (let [{:keys [message] server :from} msg
        params (:params message)
        {:keys [uri version]} params]
    (if version
      ;; if version is available, update the cache only if it is newer
      (when (> version (get-in @diagnostics-cache [uri server :version] -1))
        (swap! diagnostics-cache assoc-in [uri server] params)
        (join-diagnostics uri))
      ;; if no version, always update
      (do (swap! diagnostics-cache assoc-in [uri server] params)
          (join-diagnostics uri)))))

(defmethod methods/process-server-message "textDocument/publishDiagnostics"
  [_ diagnostics-chan output-chan _multiplexer]
  (async/go-loop []
    (when-let [msg (async/<! diagnostics-chan)]
      (when-let [result (process-server-diagnostics msg)]
        (async/>! output-chan result))
      (recur))))

(defmethod methods/process-client-message "textDocument/didClose"
  [_ client-message-chan multiplexer]
  (async/go-loop []
    (when-let [message (async/<! client-message-chan)]
      (doseq [server (mux/list-servers multiplexer)]
        (async/>! (:stdin server) message))
      (let [uri (get-in message [:params :textDocument :uri])]
        (swap! diagnostics-cache dissoc uri)))
    (recur)))
