(ns mock.basic.server
  "A runnable mock LSP server for integration testing.
   Run with: clj -M:test -m mock.basic.server [server-name]

   This server implements basic LSP protocol responses for testing."
  (:require [mock.lib :as lib]
            [jsonrpc4clj.io-chan :as io]
            [clojure.core.async :as async])
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
  (lib/respond server request {:capabilities server-capabilities
                               :server-info {:name (:name server)
                                             :version "0.1.0-test"}}))

(defmethod handle-message "initialized"
  [server _request]
  (lib/notify server "textDocument/publishDiagnostics"
              {:uri "file:///test.clj"
               :diagnostics diagnostics-item}))

(defmethod handle-message "shutdown"
  [server request]
  (lib/respond server request nil)
  (lib/close server))

(defmethod handle-message "textDocument/completion"
  [server request]
  (lib/respond server request completion-items))

(defmethod handle-message :default
  [server request]
  (let [method (:method request)
        id (:id request)]
    (when id
      (lib/error server request
                 {:code -32601
                  :message (str "Method not found: " method)})
      ;; It's a notification, just log it
      (async/>!! (:stderr server)
                 (format "[%s] receive unsupported notification %s" (:name server) method)))))

(defn -main [server-name & enabled-capabilities-keys]
  (let [server {:stdin (io/input-stream->input-chan System/in)
                :stdout (io/output-stream->output-chan System/out)
                :stderr (io/output-stream->output-chan System/err)
                :next-id (atom 1)
                :name server-name}]

    ;; start event loop
    (loop []
      (let [message (async/<!! (:stdin server))]
        (handle-message server message)
        (when-not (= (:method message) "shutdown")
          (recur))))))
