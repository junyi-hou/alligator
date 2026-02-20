(ns alligator.mock.diagnostics.s1
  (:require
   [alligator.test-utils :as utils]
   [clojure.core.async :as async]
   [jsonrpc4clj.io-chan :as io])
  (:gen-class))

(def capabilities {:diagnostic-provider true})

(def new-diagnostics {:range {:start {:line 0 :character 0} :end {:line 0 :character 5}} :message "new error from s1"})

(def old-diagnostics {:range {:start {:line 0 :character 0} :end {:line 0 :character 5}} :message "old error from s1"})

(defmulti handle-request (fn [_server request] (:method request)))

(defmethod handle-request "initialize"
  [server _]
  (utils/respond server 1 {:capabilities capabilities
                           :server-info {:name (:name server)
                                         :version "0.1.0-test"}}))

(defmethod handle-request "shutdown"
  [server request]
  (utils/respond server (:id request))

  ;; close
  (async/close! (:stdin server)))

(defn ^:private handle-notification [server]
  (async/go-loop []
    (when-let [msg (async/<! (:notification-chan server))]
      (if (= (:method msg) "textDocument/didOpen")
        ;; send two diagnostics, the first one has newer version than the second one,
        ;; and check that the client receives only the newer one
        (do (utils/notify server "textDocument/publishDiagnostics"
                          {:uri (get-in msg [:params :text-document :uri])
                           :diagnostics [new-diagnostics]
                           :version 2})

            (Thread/sleep 500)
            (utils/notify server "textDocument/publishDiagnostics"
                          {:uri (get-in msg [:params :text-document :uri])
                           :diagnostics [old-diagnostics]
                           :version 1})
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
                               :name "server-1"})]

    (async/<!! (handle-notification server))
    (async/<!! (:loop-chan server))))
