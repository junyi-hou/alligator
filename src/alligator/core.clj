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

(defn start-servers [server-config]
  (reduce-kv (fn [out k v]
               (conj out (mux/start-server k
                                           (get v "command")
                                           (map keyword (get v "capabilities"))
                                           (get v "is_default"))))
             []
             server-config))

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

(defn main-event-loop [input-stream output-stream]
  (let [input-chan (io-chan/input-stream->input-chan input-stream)
        output-chan (io-chan/output-stream->output-chan output-stream)]
    ;; Event loop 1: Read from client stdin and dispatch to servers
    (router/start-dispatching-client-messages! input-chan)
    ;; Event loop 2: Read from servers and return to client stdout
    (router/start-processing-server-messages! output-chan)))

(defn -main [& args]
  (let [{:keys [options arguments errors]} (cli/parse-opts args alligator-cli-options)]
    (when (or (:help options) errors)
      (println usage)
      (System/exit (if errors 1 0)))

    (setup-logger! options)

    (reset! mux/enabled-servers (start-servers (get-server-config options arguments)))
    (load-handlers!)
    (main-event-loop System/in System/out)
    (async/<!! states/exit-chan)
    (System/exit 0)))
