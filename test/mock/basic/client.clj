(ns mock.basic.client
  "Run with clj -M:test -m mock.basic.client"
  (:require
   [jsonrpc4clj.io-chan :as io]
   [mock.utils :as utils]
   [mock.basic.server :refer [completion-items diagnostics-item]]
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

(defn ^:private run-test [client]
  ;; test initialize
  (utils/initalize-client client capabilities root-uri)

  ;; test requests
  (let [resp (utils/request client "textDocument/completion")]
    (utils/validate-response completion-items (get-in resp [:result])))

  ;; clean up
  (Thread/sleep 1500)
  (utils/shutdown-client client))

;; (defmulti ^:private handle-request (fn [_ request] (:method request)))

;; (defmethod handle-request "workspace/applyEdit"
;;   [client request]
;;   (utils/respond client (:id request) {:applied true}))

(defn -main [& _]
  (let [client {:stdin (io/input-stream->input-chan System/in)
                :stdout (io/output-stream->output-chan System/out)
                :next-id (atom 1)
                :pending-requests (atom {})
                :name "CLIENT"}
        [notification-chan loop-chan] (utils/start-endpoint client)]
    (let [validate-chan (utils/validate-notification notification-chan {:uri "file:///test.clj"
                                                                        :diagnostics diagnostics-item})]
      (run-test client)
      (async/<!! validate-chan))
    (async/<!! loop-chan)))
