(ns mock.diagnostics.server2
  (:require
   [jsonrpc4clj.io-chan :as io]
   [mock.utils :as utils]
   [clojure.core.async :as async]
   [mock.diagnostics.server :refer [handle-request]])
  (:gen-class))

(def diagnostics1 [{:range {:start {:line 0 :character 0} :end {:line 0 :character 5}} :message "old error from s2"}])

(def diagnostics2 [{:range {:start {:line 0 :character 0} :end {:line 0 :character 5}} :message "new error from s2"}])

(defn ^:private handle-notification [server chan]
  (async/go-loop []
    (when-let [msg (async/<! chan)]
      (if (= (:method msg) "textDocument/didOpen")
        (do (Thread/sleep 100)
            (utils/notify server "textDocument/publishDiagnostics"
                          {:uri (get-in msg [:params :text-document :uri])
                           :diagnostics [diagnostics1]
                           :version 1})

            (Thread/sleep 500)
            (utils/notify server "textDocument/publishDiagnostics"
                          {:uri (get-in msg [:params :text-document :uri])
                           :diagnostics [diagnostics2]
                           :version 2})
            ;; return :done
            :done)
        ;; keep waiting if the message is not didOpen
        (recur)))))

(defn -main [server-name]
  (let [server {:stdin (io/input-stream->input-chan System/in)
                :stdout (io/output-stream->output-chan System/out)
                :request-handlers handle-request
                :next-id (atom 1)
                :name server-name}
        [notification-chan loop-chan] (utils/start-endpoint server)]

    (async/<!! (handle-notification server notification-chan))
    (async/<!! loop-chan)))
