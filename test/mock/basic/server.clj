(ns mock.basic.server
  "A runnable mock LSP server for integration testing.
   Run with: clj -M:test -m mock.basic.server [server-name]

   This server implements basic LSP protocol responses for testing."
  (:require [mock.utils :as utils]
            [jsonrpc4clj.io-chan :as io]
            [clojure.core.async :as async]
            [alligator.log :as log])
  (:gen-class))

(def server-capabilities {:completion-provider {:trigger-characters ["."]}})

(def completion-items [{:label "mock-completion"
                        :kind 1
                        :detail "Fixed mock completion item"}])
(def diagnostics-item [{:range {:start {:line 0 :character 0}
                                :end {:line 0 :character 1}}
                        :severity 1
                        :message "Mock diagnostic"}])

(defmulti ^:private handle-message
  (fn [_server request]
    (:method request)))

(defmethod handle-message "initialize"
  [server request]
  (utils/respond server (:id request) {:capabilities server-capabilities
                                       :server-info {:name (:name server)
                                                     :version "0.1.0-test"}}))

(defmethod handle-message "textDocument/completion"
  [server request]
  (utils/respond server (:id request) completion-items))

(defmethod handle-message :default
  [{:keys [name]} request]
  (log/log name (str "received unsupported request " (:method request))))

(defmethod handle-message "shutdown"
  [server request]
  (utils/respond server (:id request))

  ;; close
  (async/close! (:stdin server)))

(defn -main [server-name]
  (let [server {:stdin (io/input-stream->input-chan System/in)
                :stdout (io/output-stream->output-chan System/out)
                :request-handlers handle-message
                :next-id (atom 1)
                :name server-name}
        [_ loop-chan] (utils/start-endpoint server)]
    (utils/notify server "textDocument/publishDiagnostics"
                  {:uri "file:///test.clj"
                   :diagnostics diagnostics-item})
    (async/<!! loop-chan)))
