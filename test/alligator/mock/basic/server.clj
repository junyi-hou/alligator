(ns alligator.mock.basic.server
  (:require [clojure.core.async :as async]
            [jsonrpc4clj.io-chan :as io]
            [alligator.test-utils :as utils])
  (:gen-class))

(def server-capabilities {:completion-provider {:trigger-characters ["."]}})

(def completion-items [{:label "mock-completion"
                        :kind 1
                        :detail "Fixed mock completion item"}])

(def diagnostics-item [{:range {:start {:line 0 :character 0}
                                :end {:line 0 :character 1}}
                        :severity 1
                        :message "Mock diagnostic"}])

(defn- mock-server-handler [server request]
  (case (:method request)
    "initialize"
    (utils/respond server (:id request) {:capabilities server-capabilities
                                         :server-info {:name (:name server)
                                                       :version "0.1.0-test"}})
    "textDocument/completion"
    (utils/respond server (:id request) completion-items)

    "shutdown"
    (do (utils/respond server (:id request))
        (async/close! (:stdin server)))

    nil))

(defn -main []
  (let [server
        (utils/start-endpoint {:stdin (io/input-stream->input-chan System/in)
                               :stdout (io/output-stream->output-chan System/out)
                               :request-handlers mock-server-handler
                               :next-id (atom 1)
                               :pending-requests (atom {})
                               :name "S1"})]
    ;; Initial notification
    (utils/notify server "textDocument/publishDiagnostics"
                  {:uri "file:///test.clj"
                   :diagnostics diagnostics-item})

    (async/<!! (:loop-chan server))))
