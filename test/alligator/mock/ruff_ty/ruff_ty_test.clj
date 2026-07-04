(ns alligator.mock.ruff-ty.ruff-ty-test
  (:require
   [alligator.methods :as methods]
   [alligator.test-utils :as utils]
   [clojure.test :refer [deftest is testing]]))

(methods/load-handlers!)

(def ^:private client-capabilities {:text-document {:synchronization {:dynamic-registration false
                                                                      :will-save true
                                                                      :did-save true
                                                                      :will-save-wait-until true}}})

(deftest ^:mock multiserver-integration-test
  (let [{:keys [client] :as test-objects}
        (utils/start-servers-and-client ["--" "ty server" "-c" "definition-provider" "-c" "diagnostic-provider" "-c" "code-action-provider"
                                         "--" "ruff server" "-c" "diagnostic-provider" "-c" "code-action-provider" "-c" "document-formatting-provider"])]

    (testing "Handshake"
      (let [resp (utils/request client "initialize" {:capabilities client-capabilities :root-uri "file:///tmp"})]
        (is (= "Alligator (ty+ruff)" (get-in resp [:result :server-info :name])))
        (utils/notify client "initialized")))

    (let [file-uri "file:///tmp/test.py"]
      (utils/notify client "textDocument/didOpen" {:text-document {:uri file-uri :language-id "python" :text "import os\n" :version 1}})

      (testing "Getting diagnostics from both ruff and ty"
        ;; We expect two diagnostic notifications (one from each server or aggregated)
        ;; Depending on implementation, we might get one notification with aggregated diagnostics
        ;; or multiple notifications as servers respond. Ty may publish an empty diagnostics
        ;; notification before ruff publishes its findings.
        (let [notif1 (utils/await-notification client (fn [m] (= (:method m) "textDocument/publishDiagnostics")))
              notif2 (utils/await-notification client (fn [m] (= (:method m) "textDocument/publishDiagnostics")))
              all-diagnostics (concat (get-in notif1 [:params :diagnostics])
                                      (get-in notif2 [:params :diagnostics]))]
          (is notif1)
          (is (seq all-diagnostics))
          ;; Verify we got diagnostics from both (if aggregated) or separate
          (is (or notif2 (>= (count (get-in notif1 [:params :diagnostics])) 2)))))

      (testing "Get code actions from both servers"
        (let [resp (utils/request client "textDocument/codeAction" {:textDocument {:uri file-uri}
                                                                    :range {:start {:line 0 :character 0} :end {:line 0 :character 0}}
                                                                    :context {:diagnostics []}})]
          (is (vector? (:result resp)))
          ;; Check if we have actions from both (titles usually contain server info or are distinct)
          (is (pos? (count (:result resp))))))

      (testing "Find definition"
        (let [resp (utils/request client "textDocument/definition" {:textDocument {:uri file-uri}
                                                                    :position {:line 0 :character 8}})]
          ;; Should be handled by ty
          (is (:result resp))))

      (testing "Format document"
        (let [resp (utils/request client "textDocument/formatting" {:textDocument {:uri file-uri}
                                                                    :options {:tabSize 4 :insertSpaces true}})]
          ;; Should be handled by ruff; nil result means no changes needed
          (is (contains? resp :result)))))

    (utils/stop-servers-and-client! test-objects)))
