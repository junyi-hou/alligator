(ns mock.basic.client
  "Run with clj -M:mock-test -m mock.basic.client"
  (:require
   [clojure.core.async :as async]
   [jsonrpc4clj.io-chan :as io]
   [mock.lib :as lib]
   [mock.basic.server :refer [server-capabilities completion-items diagnostics-item]])
  (:gen-class))

(def ^:private initialize-request
  {:root-uri (format "file:/%s" (System/getProperty "user.dir"))
   :capabilities {:text-document
                  {:synchronization
                   {:dynamic-registration false
                    :will-save true
                    :will-save-wait-until true
                    :did-save true}}}})

(defn ^:private run-test [client]
  ;; test initialize
  (let [init-response (lib/request client "initialize" initialize-request)
        server-name (get-in init-response [:result :server-info :name])
        actual (get-in init-response [:result :capabilities])
        expected server-capabilities]

    (lib/validate client (= server-name "s1")
                  (format "Expected `s1`, get %s" server-name))
    (lib/validate client (= actual expected)
                  (format "Expected `%s`, get %s" expected actual))
    (lib/notify client "initialized" {}))

    ;; test diagnostics notification from server
  (let [actual (async/<!! (:stdin client))
        expected diagnostics-item]
    (lib/validate client
                  (= "textDocument/publishDiagnostics" (:method actual))
                  (format "Expected publishDiagnostics, get %s" (:method actual)))
    (lib/validate client
                  (= (get-in expected [0 :message]) (get-in actual [:params :diagnostics 0 :message]))
                  (format "Expected `%s`, get `%s`"
                          (get-in expected [0 :message])
                          (get-in actual [:params :diagnostics 0 :message]))))

    ;; test completion
  (let [completion-resp (lib/request client "textDocument/completion"
                                     {:textDocument {:uri "file:///test.clj"}
                                      :position {:line 0 :character 0}})
        actual (:result completion-resp)
        expected completion-items]
    (lib/validate client
                  (vector? actual) "Expected completion items vector")
    (lib/validate client
                  (= (count expected) (count actual))
                  (format "Expected %s item(s), get %s item(s)" (count expected) (count actual)))
    (lib/validate client
                  (= (get-in expected [0 :label]) (get-in actual [0 :label]))
                  (format "Expected `%s`, get `%s`" (get-in expected [0 :label]) (get-in actual [0 :label]))))

  ;; clean up
  (lib/request client "shutdown"))

(defn -main [& _]
  (let [client {:stdin (io/input-stream->input-chan System/in)
                :stdout (io/output-stream->output-chan System/out)
                :stderr (io/output-stream->output-chan System/err)
                :next-id (atom 1)
                :name "CLIENT"}]
    (run-test client)
    (lib/close client)))
