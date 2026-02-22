(ns alligator.core
  (:require
   [alligator.cli :refer [alligator-cli-options get-server-config usage]]
   [alligator.methods :refer [load-handlers!]]
   [alligator.multiplexer :as mux]
   [alligator.router :as router]
   [alligator.states :as states]
   [clojure.core.async :as async]
   [clojure.tools.cli :as cli]
   [jsonrpc4clj.io-chan :as io-chan]
   [taoensso.timbre :as timbre])
  (:gen-class))

(defn start-servers [multiplexer server-config]
  (doseq [[k v] server-config]
    (mux/add-server! multiplexer
                     k
                     (get v "command")
                     (map keyword (get v "capabilities"))
                     (get v "is_default"))))

(defn ^:private setup-logger! [{:keys [debug]}]
  ;; use only stderr for logging
  (timbre/merge-config!
   {:min-level (if debug :debug :warn)
    :appenders
    {:println {:enabled? false}
     :stderr {:enabled? true
              :fn (fn [data]
                    (binding [*out* *err*]
                      (println (str "Alligator" (force (:level data)) " - " (force (:msg_ data))))
                      (flush)))}}}))

(defn main-event-loop [input-stream output-stream multiplexer]
  (let [input-chan (io-chan/input-stream->input-chan input-stream)
        output-chan (io-chan/output-stream->output-chan output-stream)]
    ;; Event loop 1: Read from client stdin and dispatch to servers
    (router/start-dispatching-client-messages! input-chan multiplexer)
    ;; Event loop 2: Read from servers and return to client stdout
    (router/start-processing-server-messages! output-chan multiplexer)))

(defn -main [& args]
  (let [{:keys [options arguments errors]} (cli/parse-opts args alligator-cli-options)]
    (when (or (:help options) errors)
      (println usage)
      (System/exit (if errors 1 0)))

    (setup-logger! options)

    (let [multiplexer (mux/create-multiplexer)]
      (start-servers multiplexer (get-server-config options arguments))
      (load-handlers!)
      (main-event-loop System/in System/out multiplexer)
      (async/<!! states/exit-chan)
      (mux/stop-all-servers! multiplexer)
      (System/exit 0))))
