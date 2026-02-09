(ns mock.diagnostics.client
  (:require
   [jsonrpc4clj.io-chan :as io]
   [mock.utils :as utils]
   [mock.diagnostics.server2 :refer [diagnostics1 diagnostics2]]
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
        (when-not (= (count msgs) 3)
          (log/log "FAIL" (str "expect to receive 3 diagnostics, got " (count msgs))))
        (when-not (= (first (set (map #(get-in % [:params :uri]) msgs))) file-uri)
          (log/log "FAIL" (format "expect to get diag for %s, got %s" file-uri (map #(get-in % [:params :uri]) msgs))))

        (let [[f s t] msgs]
          (when-not (= (get-in f [:params :diagnostics])
                       [new-diagnostics])
            (log/log "FAIL" (format "expect to get diag %s, got %s"
                                    (get-in f [:params :diagnostics])
                                    [new-diagnostics])))
          (when-not (= (frequencies (get-in s [:params :diagnostics]))
                       (frequencies [diagnostics1 new-diagnostics]))
            (log/log "FAIL" (format "expect to get diag %s, got %s"
                                    [diagnostics1 new-diagnostics]
                                    (get-in s [:params :diagnostics]))))
          (when-not (= (frequencies (get-in t [:params :diagnostics]))
                       (frequencies [diagnostics2 new-diagnostics]))
            (log/log "FAIL" (format "expect to get diag %s, got %s"
                                    [diagnostics2 new-diagnostics]
                                    (get-in t [:params :diagnostics])))))))

    (async/<!! loop-chan)))
