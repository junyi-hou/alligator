(ns alligator.mock.diagnostics.s2
  (:require
   [alligator.test-utils :as utils]
   [alligator.mock.diagnostics.s1 :refer [handle-request]]
   [jsonrpc4clj.io-chan :as io]
   [clojure.core.async :as async])
  (:gen-class))

(def diagnostics1 [{:range {:start {:line 0 :character 0} :end {:line 0 :character 5}} :message "old error from s2"}])

(def diagnostics2 [{:range {:start {:line 0 :character 0} :end {:line 0 :character 5}} :message "new error from s2"}])

(defn ^:private handle-notification [server]
  (async/go-loop []
    (when-let [msg (async/<! (:notification-chan server))]
      (if (= (:method msg) "textDocument/didOpen")
        (do (Thread/sleep 100)
            (utils/notify server "textDocument/publishDiagnostics"
                          {:uri (get-in msg [:params :text-document :uri])
                           :diagnostics [diagnostics1]
                           :version 1})

            (Thread/sleep 600)
            (utils/notify server "textDocument/publishDiagnostics"
                          {:uri (get-in msg [:params :text-document :uri])
                           :diagnostics [diagnostics2]
                           :version 2})
            ;; return :done
            :done)
        ;; keep waiting if the message is not didOpen
        (recur)))))

(defn -main []
  (let [server
        (utils/start-endpoint {:stdin (io/input-stream->input-chan System/in)
                               :stdout (io/output-stream->output-chan System/out)
                               :request-handlers handle-request
                               :next-id (atom 1)
                               :name "server-2"})]

    (async/<!! (handle-notification server))
    (async/<!! (:loop-chan server))))
