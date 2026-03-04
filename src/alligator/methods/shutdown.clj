(ns alligator.methods.shutdown
  "Shut down Alligator after all servers responded to the shutdown message."
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]
   [taoensso.timbre :refer [debug]]))

(defmethod methods/process-server-message "shutdown"
  [_ input-chan output-chan multiplexer]
  (let [exit-chan (get-in multiplexer [:states :exit-chan])]
    (async/go-loop [shutdown-servers []]
      (if-let [{:keys [from message]} (async/<! input-chan)]
        (let [new-shutdown-servers (conj shutdown-servers from)]
          ;; only sends shutdown message when all servers replied
          (if (>= (count new-shutdown-servers) (count (mux/list-servers multiplexer)))
            (do
              (debug (format "[Router->Client] %s" message))
              (async/>! output-chan message)
              (async/close! exit-chan))
            (recur new-shutdown-servers)))
        ;; if channel is closed before we get all responses, still exit
        (async/close! exit-chan)))))
