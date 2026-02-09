(ns mock.diagnostics.client
  (:require
   [jsonrpc4clj.io-chan :as io]
   [mock.utils :as utils]
   [mock.diagnostics.server :refer [new-diagnostics]]
   [clojure.core.async :as async]
   [alligator.log :as log])
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

(def ^:private file-uri
  (format "%s/foo.clj" root-uri))

(defn ^:private test-diagnostics [notification-chan]
  (async/go-loop [results []]
    (if-let [msg (async/<! notification-chan)]
      (if (= (:method msg) "textDocument/publishDiagnostics")
        (recur (conj results msg))
        (recur results))
      results)))

(defn -main [& _]
  (let [client {:stdin (io/input-stream->input-chan System/in)
                :stdout (io/output-stream->output-chan System/out)
                :next-id (atom 1)
                :pending-requests (atom {})
                :name "CLIENT"}
        [notification-chan loop-chan] (utils/start-endpoint client)]

    ;; initialize
    (utils/initalize-client client capabilities root-uri)

    ;; open a document
    (Thread/sleep 100)
    (utils/notify client "textDocument/didOpen" {:text-document {:uri file-uri}})

    (let [diag-chan (test-diagnostics notification-chan)]
      (Thread/sleep 2000)
      (utils/shutdown-client client)
      (let [msgs (async/<!! diag-chan)]
        (when-not (= (count msgs) 1)
          (log/log "FAIL" (str "expect to receive 1 diagnostics, got " (count msgs))))

        (let [msg (first msgs)]
          (when-not (= (get-in msg [:params :uri]) file-uri)
            (log/log "FAIL" (format "expect to get diag for %s, got %s" file-uri (get-in msg [:params :uri]))))

          (when-not (= (get-in msg [:params :diagnostics]) new-diagnostics)
            (log/log "FAIL" (format "expect to get diag %s, got %s" new-diagnostics (get-in msg [:params :diagnostics])))))))

    (async/<!! loop-chan)))
