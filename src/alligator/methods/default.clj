(ns alligator.methods.default
  (:require
   [clojure.core.async :as async]
   [alligator.multiplexer :as mux]
   [alligator.log :as log]
   [alligator.methods :as methods]
   [alligator.states :refer [server-request-id-mapping]]))

;; unspecified server message (notification, request, or responses) goes directly to
;; stdout
(defmethod methods/process-server-message :default
  [_ msg-chan output-chan]
  (async/go-loop []
    (when-let [{:keys [message]} (async/<! msg-chan)]
      ;; (log/log "Router -> Client" (format "method: %s" (:method message)))
      (async/>! output-chan message)
      (recur))))

;; server error goes directly to client
(defmethod methods/process-server-message :error
  [_ msg-chan output-chan]
  (async/go-loop []
    (when-let [{:keys [message]} (async/<! msg-chan)]
      (async/>! output-chan message)
      (recur))))

;; illegal server message goes to stdout only
(defmethod methods/process-server-message :illegal-server-message-type
  [_ msg-chan _]
  (async/go-loop []
    (when-let [{:keys [message]} (async/<! msg-chan)]
      ;; (log/log "Router" (format "getting illegal server message %s" message))
      (recur))))

;; client 

;; unspecified client request goes to server that can handle it
(defmethod methods/process-client-message :default
  [_ input-chan]
  (async/go-loop []
    (when-let [message (async/<! input-chan)]
      (let [servers (mux/server-accept-method (:method message))]
        ;; (log/log "Router -> Server" (format "[default] method: %s, routing to: %s"
        ;;                                     (:method message)
        ;;                                     (mapv :name servers)))
        (doseq [server servers]
          (async/>! (:stdin server) message)))
      (recur))))

;; client notifications go to every server
(defmethod methods/process-client-message :notification
  [_ input-chan]
  (async/go-loop []
    (when-let [message (async/<! input-chan)]
      (let [servers @mux/enabled-servers]
        ;; (log/log "Router -> Server" (format "[notification] method: %s, routing to all: %s"
        ;;                                     (:method message)
        ;;                                     (mapv :name servers)))
        (doseq [server servers]
          (async/>! (:stdin server) message)))
      (recur))))

;; client responses go to only the server who sends the requests
(defmethod methods/process-client-message :response
  [_ input-chan]
  (async/go-loop []
    (when-let [message (async/<! input-chan)]
      (let [remapped-id (:id message)
            {:keys [server-name original-id]} (get @server-request-id-mapping remapped-id)
            server (some #(when (= (:name %) server-name) %) @mux/enabled-servers)]
        ;; (log/log "Router -> Server" (format "[response] id: %s, routing to: %s"
        ;;                                     remapped-id
        ;;                                     server-name))
        (when (async/>! (:stdin server) (assoc message :id original-id))
          (swap! server-request-id-mapping dissoc remapped-id))))
    (recur)))

;; client error go to only the server who sends the requests
(defmethod methods/process-client-message :error
  [_ input-chan]
  (async/go-loop []
    (when-let [message (async/<! input-chan)]
      (let [remapped-id (:id message)
            {:keys [server-name original-id]} (get @server-request-id-mapping remapped-id)
            server (some #(when (= (:name %) server-name) %) @mux/enabled-servers)]
        (when (async/>! (:stdin server) (assoc message :id original-id))
          (swap! server-request-id-mapping dissoc remapped-id))
        (recur)))))

;; illegal client message goes to stderr only
(defmethod methods/process-client-message :illegal-client-message-type
  [_ msg-chan]
  (async/go-loop []
    (when-let [message (async/<! msg-chan)]
      (log/log "Router" (format "getting illegal client message %s" message))
      (recur))))
