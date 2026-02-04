(ns mock.lib
  (:require
   [clojure.core.async :as async]
   [alligator.log :as log]))

(defn request
  "Send request from SENDER to its stdout and expecting to receive a response in the
   stdin of the channel. Log the outgoing request to stderr and return the incoming
   response."
  ([sender method] (request sender method nil 5000))
  ([sender method params] (request sender method params 5000))
  ([{:keys [stdin stdout next-id name]} method params timeout-ms]
   (let [msg {:jsonrpc "2.0" :id @next-id :method method :params params}]
     (async/>!! stdout msg)
     (log/log name (format "sending %s with %s"
                           method
                           (or params "no params")))

     (swap! next-id inc)

     ;; now read the server response
     (async/alt!!
       stdin ([response] response)
       (async/timeout timeout-ms) ([_] ::timeout)))))

(defn notify
  "Send notification using SENDER's stdout. Log the outgoing notification in the stderr."
  [{:keys [stdout name]} method params]
  (let [msg {:jsonrpc "2.0" :method method :params params}]

    (async/>!! stdout msg)
    (log/log name (format "sending %s with params %s" method params))))

(defn receive-notification
  "Read the top of the SENDER's stdin queue, and return the result."
  ([sender] (receive-notification sender 5000))
  ([{:keys [stdin]} timeout-ms]
   (async/alt!!
     stdin ([response] response)
     (async/timeout timeout-ms) ([_] ::timeout))))

(defn respond
  "Write a response to SENDER's stdout given an incoming ID. Log the response result in stderr."
  [{:keys [stdout name]} request result]
  (let [id (:id request)
        msg {:jsonrpc "2.0" :id id :result result}]
    (async/>!! stdout msg)
    (log/log name (format "responding back to %s with result %s" id result))))

(defn error [{:keys [stdout name]} request error]
  (let [id (:id request)
        msg {:jsonrpc "2.0" :id id :error error}]
    (log/log name (format "errors out with %s" error))
    (async/>!! stdout msg)))

(defn close
  "Close all channels of SENDER and exit the script."
  ([sender] (close sender 0))
  ([{:keys [stdout stdin]} code]
   (async/close! stdout)
   (async/close! stdin)
   ;; Wait briefly to ensure channel close operations complete
   (shutdown-agents)
   (System/exit code)))

(defn validate
  "Check if CONDITION is true, if not log to stderr and exit with code 1.
   Optionally provide MESSAGE for context when the validation fails.
   Returns true if condition passes, exits if it fails."
  ([sender condition]
   (validate sender condition "Validation failed"))
  ([sender condition message]
   (if condition
     true
     (do
       (binding [*out* *err*]
         (println "[VALIDATION ERROR]" message)
         (flush))
       (close sender 1)))))
