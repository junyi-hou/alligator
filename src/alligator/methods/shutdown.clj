(ns alligator.methods.shutdown
  "Shut down Alligator after all servers responded to the shutdown message."
  (:require
   [clojure.core.async :as async]
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]))

(defmethod  methods/process-server-message "shutdown"
  [_ input-chan output-chan]
  (async/go-loop [shutdown-servers []]
    (if-let [{:keys [from message]} (async/<! input-chan)]
      (let [new-shutdown-servers (conj shutdown-servers from)]
        ;; only sends shutdown message when all servers replied
        (if (>= (count new-shutdown-servers) (count @mux/enabled-servers))
          (do
            (async/>! output-chan message)
            (async/close! mux/exit-chan))
          (recur new-shutdown-servers)))
      ;; if channel is closed before we get all responses, still exit
      (async/close! mux/exit-chan))))
