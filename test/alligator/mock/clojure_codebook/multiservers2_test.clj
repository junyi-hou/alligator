(ns alligator.mock.clojure-codebook.multiservers2-test
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
        (utils/start-servers-and-client ["--" "clojure-lsp listen" "-d"
                                         "--" "codebook-lsp serve" "-c" "diagnostic-provider" "-c" "code-action-provider"])]

    (testing "Handshake"
      (let [resp (utils/request client "initialize" {:capabilities client-capabilities :root-uri "file:///tmp"})]
        (is (= "Alligator (clojure-lsp+codebook-lsp)" (get-in resp [:result :server-info :name])))
        (utils/notify client "initialized")))

    (let [file-uri "file:///tmp/test.clj"]
      (utils/notify client "textDocument/didOpen" {:text-document {:uri file-uri :language-id "clojure" :text "(ns wrong-name \"thi is a typo\")" :version 1}})
      (testing "Getting diagnostics from both servers"
        ;; We expect two diagnostic notifications (one from each server or aggregated)
        ;; Depending on implementation, we might get one notification with aggregated diagnostics
        ;; or multiple notifications as servers respond.
        (let [notif1 (utils/await-notification client (fn [m] (= (:method m) "textDocument/publishDiagnostics")) 10000)
              notif2 (utils/await-notification client (fn [m] (= (:method m) "textDocument/publishDiagnostics")) 10000)]
          (is notif1)
          (is (seq (get-in notif1 [:params :diagnostics])))
          ;; Verify we got something that looks like it's from both (if aggregated) or separate
          (is (or notif2 (>= (count (get-in notif1 [:params :diagnostics])) 2))))))

    (utils/stop-servers-and-client! test-objects)))
