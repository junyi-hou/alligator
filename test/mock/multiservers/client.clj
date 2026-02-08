(ns mock.multiservers.client
  (:require
   [clojure.core.async :as async]
   [mock.utils :as utils]
   [jsonrpc4clj.io-chan :as io])
  (:gen-class))

(def ^:private capabilities
  {:text-document
   {:synchronization
    {:dynamic-registration false
     :will-save true
     :will-save-wait-until true
     :did-save true}}})

(def ^:private root-uri
  (format "file:/%s" (System/getProperty "user.dir")))

(defn ^:private run-test [client]
  ;; test initialization
  (utils/initalize-client client capabilities root-uri)

  (Thread/sleep 500)
  (utils/shutdown-client client))

(defn -main [& _]
  (let [client {:stdin (io/input-stream->input-chan System/in)
                :stdout (io/output-stream->output-chan System/out)
                :next-id (atom 1)
                :pending-requests (atom {})
                :name "CLIENT"}
        [_ loop-chan] (utils/start-endpoint client)]
    (run-test client)
    (async/<!! loop-chan)))
