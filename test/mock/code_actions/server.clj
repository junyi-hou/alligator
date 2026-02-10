(ns mock.code-actions.server
  "Testing textDocument/codeActoon workflow.

   This tests two workflows:
   textDocument/codeAction request ->
   textDocument/codeAction response ->
   codeAction/resolve request ->
   codeAction/resolve response,

   and

   textDocument/codeAction request ->
   textDocument/codeAction response ->
   workspace/executeCommand request ->
   workspace/executeCommand response

   run: clj -M:test -m mock.code-actions.server [server-name]
  "
  (:require [mock.utils :as utils]
            [jsonrpc4clj.io-chan :as io]
            [clojure.core.async :as async]
            [alligator.log :as log])
  (:gen-class))

(def ^:private server-capabilities {:code-action-provider {:resolve-provider true}})

(defmulti ^:private handle-message (fn [_server request] (:method request)))

(defmethod handle-message "initialize"
  [server request]
  (utils/respond server (:id request) {:capabilities
                                       (merge server-capabilities
                                              {:execute-command-provider
                                               {:command [(str "command-" (:name server))]}})
                                       :server-info {:name (:name server)
                                                     :version "0.1.0-test"}}))

(defmethod handle-message :default
  [{:keys [name]} request]
  (log/log name (str "received unsupported request " (:method request))))

(defmethod handle-message "shutdown"
  [server request]
  (utils/respond server (:id request))
  (async/close! (:stdin server)))

(defn gen-code-action-item [server-name]
  {:title (str "edit-from" server-name) :kind "quickfix" :data {:action "foo"}})

(defn gen-code-action-command [server-name]
  {:title (str "command-from-" server-name) :command (str "command" server-name)})

(defn edit-body [server-name]
  {:change {"file:///foo.clj" [{:range {:start {:line 10 :character 0}
                                        :end {:line 10 :character 10}}
                                :new-text (str "new-edit-from-" server-name)}]}})

(defmethod handle-message "textDocument/codeAction"
  [server request]
  (utils/respond server (:id request) [(gen-code-action-command (:name server))
                                       (gen-code-action-item (:name server))]))

(defmethod handle-message "codeAction/resolve"
  [server request]
  ;; validate that the request is correctly amended
  (utils/validate-response (:params request)
                           (gen-code-action-item (:name server)))
  (utils/respond server (:id request)
                 {:title (str "edit-from" (:name server)) :kind "quickfix"
                  :edit (edit-body (:name server))}))

(defmethod handle-message "workspace/executeCommand"
  [server request]
  (utils/validate-response {:title (str "command-from-" (:name server)) :command (str "command" (:name server))}
                           (:params request))

  (utils/request server
                 "workspace/applyEdit"
                 {:label (str "edit-from" (:name server))
                  :edit (edit-body (:name server))}))

(defn -main [server-name]
  (let [server {:stdin (io/input-stream->input-chan System/in)
                :stdout (io/output-stream->output-chan System/out)
                :request-handlers handle-message
                :next-id (atom 100)
                :name server-name}
        [_ loop-chan] (utils/start-endpoint server)]
    (async/<!! loop-chan)))
