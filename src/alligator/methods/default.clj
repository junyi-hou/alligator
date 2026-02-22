(ns alligator.methods.default
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [alligator.states :as states]
   [clojure.core.async :as async]
   [taoensso.timbre :refer [warn]]))

;; unspecified server message (notification, request, or responses) goes directly to
;; stdout
(defmethod methods/process-server-message :default
  [_ msg-chan output-chan _]
  (async/go-loop []
    (when-let [{:keys [message]} (async/<! msg-chan)]
      (async/>! output-chan message)
      (recur))))

;; server error goes directly to client
(defmethod methods/process-server-message :error
  [_ msg-chan output-chan _]
  (async/go-loop []
    (when-let [{:keys [message]} (async/<! msg-chan)]
      (async/>! output-chan message)
      (recur))))

;; illegal server message goes to stdout only
(defmethod methods/process-server-message :illegal-server-message-type
  [_ msg-chan _ _]
  (async/go-loop []
    (when-let [{:keys [from message]} (async/<! msg-chan)]
      (warn (format "[%s->Router] Getting invalid message %s" from message))
      (recur))))

;; client 

;; unspecified client request goes to server that can handle it
(defmethod methods/process-client-message :default
  [_ input-chan multiplexer]
  (async/go-loop []
    (when-let [message (async/<! input-chan)]
      (let [servers (mux/servers-for-method multiplexer (:method message))]
        (doseq [server servers]
          (async/>! (:stdin server) message)))
      (recur))))

;; client notifications go to every server
(defmethod methods/process-client-message :notification
  [_ input-chan multiplexer]
  (async/go-loop []
    (when-let [message (async/<! input-chan)]
      (let [servers (mux/list-servers multiplexer)]
        (doseq [server servers]
          (async/>! (:stdin server) message)))
      (recur))))

;; client responses go to only the server who sends the requests
(defmethod methods/process-client-message :response
  [_ input-chan multiplexer]
  (async/go-loop []
    (when-let [message (async/<! input-chan)]
      (let [remapped-id (:id message)
            {:keys [server-name original-id]} (get @states/server-request-id-mapping remapped-id)
            server (mux/get-server-by-name multiplexer server-name)]
        (when (async/>! (:stdin server) (assoc message :id original-id))
          (swap! states/server-request-id-mapping dissoc remapped-id))))
    (recur)))

;; client error go to only the server who sends the requests
(defmethod methods/process-client-message :error
  [_ input-chan multiplexer]
  (async/go-loop []
    (when-let [message (async/<! input-chan)]
      (let [remapped-id (:id message)
            {:keys [server-name original-id]} (get @states/server-request-id-mapping remapped-id)
            server (mux/get-server-by-name multiplexer server-name)]
        (when (async/>! (:stdin server) (assoc message :id original-id))
          (swap! states/server-request-id-mapping dissoc remapped-id))
        (recur)))))

;; illegal client message goes to stderr only
(defmethod methods/process-client-message :illegal-client-message-type
  [_ msg-chan _]
  (async/go-loop []
    (when-let [message (async/<! msg-chan)]
      (warn (format "[Client->Router] Getting invalid message %s" message))
      (recur))))
