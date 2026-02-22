(ns alligator.test-utils
  (:require
   [alligator.cli :as cli]
   [alligator.core :as core]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]
   [jsonrpc4clj.coercer :as coercer]
   [jsonrpc4clj.io-chan :as io-chan]
   [taoensso.timbre :as timbre])
  (:import
   (java.io PipedInputStream PipedOutputStream)))

;; for mock tests 

(defn request
  "Send request from ENDPOINT to its stdout and expecting to receive a response in the
   responses channel. Log the outgoing request to stderr.

   Return the response."
  ([endpoint method] (request endpoint method nil))
  ([{:keys [stdout next-id name pending-requests]} method params]
   (let [id @next-id
         msg {:jsonrpc "2.0" :id id :method method :params params}
         response-chan (async/promise-chan)]
     (async/>!! stdout msg)
     (timbre/debug (format "[%s] sending %s with %s (id: %s)"
                           name
                           method
                           (or params "no param")
                           id))

     (swap! pending-requests assoc id response-chan)
     (swap! next-id inc)
     (let [timeout-chan (async/timeout 15000)
           [val port] (async/alts!! [response-chan timeout-chan])]
       (if (= port timeout-chan)
         (do
           (timbre/error (format "[%s] request %s (id: %s) timed out" name method id))
           nil)
         val)))))

(defn notify
  "Send notification using ENDPOINT's stdout. Log the outgoing notification in the stderr."
  ([endpoint method] (notify endpoint method nil))
  ([{:keys [stdout name]} method params]
   (let [msg {:jsonrpc "2.0" :method method :params params}]

     (async/>!! stdout msg)
     (timbre/debug (format "[%s] sending %s with %s" name method (or params "no param"))))))

(defn respond
  "Send response with RESULT to an request with ID."
  ([endpoint id] (respond endpoint id nil))
  ([{:keys [stdout name]} id result]
   (let [msg {:jsonrpc "2.0" :id id :result result}]
     (async/>!! stdout msg)
     (timbre/debug (format "[%s] responding to %s with result %s" name id result)))))

(defn shutdown-client [client]
  (when (request client "shutdown")
    (notify client "exit")
    ;; sends nil to client stdin to close all channels
    (async/close! (:stdin client))))

(defn start-endpoint [{:keys [stdin pending-requests request-handlers name] :as endpoint}]
  (let [notifications-chan (async/chan 100)
        loop-chan
        (async/go-loop []
          (if-let [msg (async/<! stdin)]
            (do (case (coercer/input-message-type msg)
                  :notification
                  (async/>! notifications-chan msg)
                  :response.result
                  (if-let [response-chan (get @pending-requests (:id msg))]
                    (do
                      (async/>! response-chan msg)
                      (swap! pending-requests dissoc (:id msg)))
                    (timbre/error (format "[%s] received stray response with id %s" name (:id msg))))
                  :response.error
                  (if-let [response-chan (get @pending-requests (:id msg))]
                    (do
                      (async/>! response-chan msg)
                      (swap! pending-requests dissoc (:id msg)))
                    (timbre/error (format "[%s] received stray response with id %s" name (:id msg))))
                  :request
                  (if request-handlers
                    (request-handlers endpoint msg)
                    (timbre/error (format "[%s] cannot handle incoming request" name)))
                  (timbre/error (format "[%s] received invalid message %s" name msg)))
                (recur))
            ;; Cleanup on close
            (doseq [ch (conj (vals @pending-requests) notifications-chan)]
              (async/close! ch))))]
    (-> endpoint
        (assoc :notification-chan notifications-chan)
        (assoc :loop-chan loop-chan))))

(defn ^:private start-mock-client
  [in-stream out-stream request-handlers]
  (start-endpoint {:name "CLIENT"
                   :stdin (io-chan/input-stream->input-chan in-stream)
                   :stdout (io-chan/output-stream->output-chan out-stream)
                   :next-id (atom 1)
                   :pending-requests (atom {})
                   :request-handlers request-handlers}))

(defn start-servers-and-client
  ([server-command] (start-servers-and-client server-command nil))
  ([server-command client-request-handlers]
   (let [alligator-in (PipedInputStream.)
         client-in (PipedInputStream.)
         client->alligator (PipedOutputStream. alligator-in)
         alligator->client (PipedOutputStream. client-in)
         client (start-mock-client client-in client->alligator client-request-handlers)
         multiplexer (mux/create-multiplexer)]

     (core/start-servers multiplexer (cli/get-server-config {} server-command))

     (async/thread
       (core/main-event-loop alligator-in alligator->client multiplexer))

     {:client client
      :multiplexer multiplexer
      :streams [alligator-in client-in client->alligator alligator->client]})))

(defn stop-servers-and-client! [{:keys [client multiplexer streams]}]
  (shutdown-client client)
  (mux/stop-all-servers! multiplexer)
  (doseq [s streams]
    (try
      (.close s)
      ;; Ignore already closed/broken pipes
      (catch java.io.IOException _))))
