(ns alligator.test-utils
  (:require
   [clojure.core.async :as async]
   [clojure.java.process :as proc]
   [jsonrpc4clj.coercer :as coercer]
   [jsonrpc4clj.io-chan :as io-chan]
   [taoensso.timbre :as timbre]))

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
     (let [timeout-chan (async/timeout 5000)
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
    (notify client "exit")))

(defn await-notification
  "Keep reading from the notification channel of ENDPOINT until a message matches PRED or timeout.
   Returns the matching message or nil if timed out."
  ([endpoint pred] (await-notification endpoint pred 2000))
  ([{:keys [notification-chan]} pred timeout-ms]
   (let [timeout-chan (async/timeout timeout-ms)]
     (loop []
       (let [[msg channel] (async/alts!! [notification-chan timeout-chan])]
         (cond
           (= channel timeout-chan) nil
           (pred msg) msg
           :else (recur)))))))

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

(defn ^:private safe-out-stream [^java.io.OutputStream os]
  (proxy [java.io.OutputStream] []
    (write
      ([b]
       (try
         (if (integer? b)
           (.write os ^int b)
           (.write os ^bytes b))
         (catch java.io.IOException _)))
      ([b off len]
       (try
         (.write os ^bytes b ^int off ^int len)
         (catch java.io.IOException _))))
    (flush []
      (try (.flush os) (catch java.io.IOException _)))
    (close []
      (try (.close os) (catch java.io.IOException _)))))

(defn ^:private safe-in-stream [^java.io.InputStream is]
  (proxy [java.io.InputStream] []
    (read
      ([]
       (try (.read is) (catch java.io.IOException _ -1)))
      ([b]
       (try (.read is ^bytes b) (catch java.io.IOException _ -1)))
      ([b off len]
       (try (.read is ^bytes b ^int off ^int len) (catch java.io.IOException _ -1))))
    (close []
      (try (.close is) (catch java.io.IOException _)))))

(defn ^:private start-mock-client
  [in-stream out-stream request-handlers]
  (start-endpoint {:name "CLIENT"
                   :stdin (io-chan/input-stream->input-chan (safe-in-stream in-stream))
                   :stdout (io-chan/output-stream->output-chan (safe-out-stream out-stream))
                   :next-id (atom 1)
                   :pending-requests (atom {})
                   :request-handlers request-handlers}))

(defn start-servers-and-client
  ([server-command] (start-servers-and-client server-command nil))
  ([server-command client-request-handlers]
   (let [alligator-args (into ["clj" "-M:test" "-m" "alligator.core" "--debug"] server-command)
         process (apply proc/start {:err :inherit} alligator-args)
         alligator-stdout (proc/stdout process)
         alligator-stdin (proc/stdin process)
         client (start-mock-client alligator-stdout alligator-stdin client-request-handlers)]
     {:client client
      :server process})))

(defn stop-servers-and-client! [{:keys [client server]}]
  (shutdown-client client)
  (async/close! (:stdin client))
  (async/close! (:stdout client))
  (when (.isAlive server)
    (.destroy server)))
