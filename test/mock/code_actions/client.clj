(ns mock.code-actions.client
  (:require
   [jsonrpc4clj.io-chan :as io]
   [mock.utils :as utils]
   [mock.code-actions.server :as server]
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
  "file:///test")

(defmulti ^:private handle-server-requests (fn [_client request] (:method request)))

(defmethod handle-server-requests :default
  [_ request]
  (utils/log "CLIENT" (str "received unsupported request " (:method request))))

(defmethod handle-server-requests "workspace/applyEdit"
  [client request]
  (utils/log "CLIENT" (format "received workspace/applyEdit: %s" (:params request)))
  (utils/respond client (:id request) {:applied true}))

(defn ^:private run-client [client]
  (utils/initalize-client client capabilities root-uri)

  (Thread/sleep 500)
  (let [resp (utils/request client "textDocument/codeAction"
                            {:textDocument {:uri (str root-uri "/file.clj")}
                             :range {:start {:line 0 :character 0}
                                     :end {:line 0 :character 0}}
                             :context {:diagnostics []}})
        actions (:result resp)]
    (utils/log "CLIENT" (format "get list of code action %s" actions))

    ;; Pick a command from s1
    (let [expected-command (server/gen-code-action-command "s1")
          s1-command (first (filter #(= (:title %) (:title expected-command)) actions))]
      (if s1-command
        (let [resp (utils/request client "workspace/executeCommand" s1-command)]
          (utils/validate-response {:applied true} (:result resp)))
        (utils/log "FAIL" (str (:title expected-command) " not found"))))

    (Thread/sleep 500)

    ;; Pick a code action from s2
    (let [expected-item (server/gen-code-action-item "s2")
          s2-item (first (filter #(= (:title %) (:title expected-item)) actions))]
      (if s2-item
        (let [resp (utils/request client "codeAction/resolve" s2-item)
              result (:result resp)
              expected {:title (:title expected-item)
                        :kind (:kind expected-item)
                        :edit (server/edit-body "s2")}]
          (utils/validate-response (:title expected) (:title result))
          (utils/validate-response (:kind expected) (:kind result)))
        (utils/log "FAIL" (str (:title expected-item) " not found"))))

    (Thread/sleep 500)
    (utils/shutdown-client client)))

(defn -main [& _]
  (let [client {:stdin (io/input-stream->input-chan System/in)
                :stdout (io/output-stream->output-chan System/out)
                :next-id (atom 1)
                :pending-requests (atom {})
                :request-handlers handle-server-requests
                :name "CLIENT"}
        [notification-chan loop-chan] (utils/start-endpoint client)]
    ;; Run client logic in separate thread so it doesn't block the event loop
    (async/thread (run-client client))
    (async/<!! loop-chan)))
