(ns alligator.core
  (:require
   [clojure.java.io :as io]
   [clojure.core.async :as async]
   [toml-clj.core :as toml]
   [jsonrpc4clj.io-chan :as io-chan]
   [alligator.multiplexer :as mux]
   [alligator.methods :refer [load-handlers!]]
   [alligator.router :as router])
  (:gen-class))

(defn -main [& args]
  (let [config-file (or (first args) "config.toml")
        config (with-open [rdr (io/reader config-file)]
                 (toml/read rdr))]

    ;; Start all configured LSP servers
    (load-handlers!)
    (reset! mux/enabled-servers
            (reduce-kv (fn [out k v]
                         (conj out (mux/start-server k
                                                     (get v "command")
                                                     (map keyword (get v "capabilities"))
                                                     (get v "is_default"))))
                       []
                       config))

    ;; Set up client I/O channels
    (let [input-chan (io-chan/input-stream->input-chan System/in)
          output-chan (io-chan/output-stream->output-chan System/out)]

      ;; Event loop 1: Read from client stdin and dispatch to servers
      (router/start-dispatching-client-messages! input-chan)

      ;; Event loop 2: Read from servers and return to client stdout
      (router/start-processing-server-messages! output-chan)

      ;; Keep main thread alive
      (async/<!! mux/exit-chan)
      (System/exit 0))))
