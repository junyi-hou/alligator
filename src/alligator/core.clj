(ns alligator.core
  (:require
   [clojure.java.io :as io]
   [clojure.core.async :as async]
   [clojure.tools.cli :as cli]
   [toml-clj.core :as toml]
   [jsonrpc4clj.io-chan :as io-chan]
   [taoensso.timbre :as timbre]
   [alligator.multiplexer :as mux]
   [alligator.methods :refer [load-handlers!]]
   [alligator.states :refer [alligator-cli-options server-options]]
   [alligator.router :as router])
  (:gen-class))

(defn ^:private get-server-config [options arguments]
  (cond
    (seq arguments)
    (loop [config {}
           args arguments]
      (if (seq args)
        (let [[this-server-config other-configs] (split-with #(not= % "--server") (rest args))
              {server-option :options command :arguments} (cli/parse-opts this-server-config server-options)]
          (recur (assoc config
                        (first command)
                        {"command" command
                         "capabilities" (:capabilities server-option)
                         "is_default" (:default server-option)})
                 other-configs))
        config))
    (:config options)
    (with-open [rdr (io/reader (:config options))]
      (toml/read rdr))))

(defn ^:private start-servers [server-config]
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
                      (println (force (:output_ data)))
                      (flush)))}}}))

(defn -main [& args]
  (let [{:keys [options arguments]} (cli/parse-opts args alligator-cli-options)
        config (get-server-config options arguments)]

    (setup-logger! options)
    (reset! mux/enabled-servers (start-servers config))
    (load-handlers!)

    (let [input-chan (io-chan/input-stream->input-chan System/in)
          output-chan (io-chan/output-stream->output-chan System/out)]

      ;; Event loop 1: Read from client stdin and dispatch to servers
      (router/start-dispatching-client-messages! input-chan)

      ;; Event loop 2: Read from servers and return to client stdout
      (router/start-processing-server-messages! output-chan)

      ;; Keep main thread alive
      (async/<!! alligator.states/exit-chan)
      (System/exit 0))))
