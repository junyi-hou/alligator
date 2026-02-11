(ns mock.code-actions.client
  (:require
   [jsonrpc4clj.io-chan :as io]
   [mock.utils :as utils]
    [clojure.core.async :as async])
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

(defmulti ^:private handle-server-requests (fn [_client request] (:method request)))

(defmethod handle-server-requests :default
  [_ request]
  (utils/log "CLIENT" (str "received unsupported request " (:method request))))

(defmethod handle-server-requests "workspace/executeCommand"
  [_ request]
  (utils/validate-response request
                           1))

(defn ^:private run-client [client]
  (utils/initalize-client client capabilities root-uri)

  (Thread/sleep 500)
  (let [resp (utils/request client "textDocument/codeAction")]
    (utils/log "CLIENT" "get list of code action")
    (Thread/sleep 500)))

(defn -main [& _]
  (let [client {:stdin (io/input-stream->input-chan System/in)
                :stdout (io/output-stream->output-chan System/out)
                :next-id (atom 1)
                :pending-requests (atom {})
                :request-handlers handle-server-requests
                :name "CLIENT"}
        [_ loop-chan] (utils/start-endpoint client)]
    (run-client client)
    (async/<!! loop-chan)))
