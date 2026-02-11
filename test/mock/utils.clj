(ns mock.utils
  (:require
   [clojure.core.async :as async]
   [jsonrpc4clj.coercer :as coercer]))

(defn log [name msg]
  (binding [*out* *err*]
    (println (format "[%s] %s" name msg))))

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
     (log name (format "sending %s with %s (id: %s)"
                       method
                       (or params "no params")
                       id))

     (swap! pending-requests assoc id response-chan)
     (swap! next-id inc)
     (async/<!! response-chan))))

(defn notify
  "Send notification using ENDPOINT's stdout. Log the outgoing notification in the stderr."
  ([endpoint method] (notify endpoint method nil))
  ([{:keys [stdout name]} method params]
   (let [msg {:jsonrpc "2.0" :method method :params params}]

     (async/>!! stdout msg)
     (log name (format "sending %s with params %s" method params)))))

(defn respond
  "Send response with RESULT to an request with ID."
  ([endpoint id] (respond endpoint id nil))
  ([{:keys [stdout name]} id result]
   (let [msg {:jsonrpc "2.0" :id id :result result}]
     (async/>!! stdout msg)
     (log name (format "responding to %s with result %s" id result)))))

(defn shutdown-client [client]
  (when (request client "shutdown")
    (notify client "exit")
    ;; sends nil to client stdin to close all channels
    (async/close! (:stdin client))))

(defn initalize-client [client capabilities root-uri]
  (when-let [resp (request client "initialize" {:capabilities capabilities :root-uri root-uri})]
    (log "CLIENT" (format "connected to %s v%s" (get-in resp [:result :server-info :name]) (get-in resp [:result :server-info :version])))
    (notify client "initialized")))

(defn start-endpoint [{:keys [stdin pending-requests request-handlers name] :as endpoint}]
  (let [notifications-chan (async/chan 100)
        loop-chan
        (async/go-loop []
          (if-let [msg (async/<! stdin)]
            (do (case (coercer/input-message-type msg)
                  :notification
                  (do (log name (str "received notification " (:method msg)))
                      (async/>! notifications-chan msg))
                  :response.result
                  (if-let [response-chan (get @pending-requests (:id msg))]
                    (do
                      (async/>! response-chan msg)
                      (swap! pending-requests dissoc (:id msg)))
                    (log name (str "received stray response with id " (:id msg))))
                  :response.error
                  (if-let [response-chan (get @pending-requests (:id msg))]
                    (do
                      (async/>! response-chan msg)
                      (swap! pending-requests dissoc (:id msg)))
                    (log name (str "received stray responses with id " (:id msg))))
                  :request
                  (do (log name (str "received request " (:method msg)))
                      (request-handlers endpoint msg))
                  (log name (str "received invalid message " msg)))
                (recur))
                      ;; Cleanup on close
            (doseq [ch (conj (vals @pending-requests) notifications-chan)]
              (async/close! ch))))]
    [notifications-chan loop-chan]))

(defn validate-response [expected actual]
  (when-not (= expected actual)
    (log "FAIL" (format "Expect %s, got %s" expected actual))))

(defn validate-notification [notification-chan & expected-messages]
  (async/go-loop [expected expected-messages]
    (if-let [msg (async/<! notification-chan)]
      ;; ignore notifications that are not expected
      (if-let [match (some #(when (= (:params msg) %) %) expected)]
        (recur (remove #{match} expected))
        (do (log "CLIENT" (format "drop unexpected notification %s" msg))
            (recur expected)))
      ;; report when the channel closed
      (when (>= (count expected) 1)
        (log "FAIL" (format "Missing expected notifications %s" expected))))))
