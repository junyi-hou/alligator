(ns alligator.mock.clojure-lsp.clojure-lsp-test
  (:require
   [alligator.methods :as methods]
   [alligator.test-utils :as utils]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(methods/load-handlers!)

(def ^:private client-capabilities {:text-document {:synchronization {:dynamic-registration false
                                                                      :will-save true
                                                                      :did-save true
                                                                      :will-save-wait-until true}}})

(def ^:private cwd (.getCanonicalPath (io/file ".")))

(def ^:private doc-content "(ns alligator.mock.clojure-lsp.foo)")

(deftest ^:mock one-server-integration-test
  (let [{:keys [client] :as test-objects}
        (utils/start-servers-and-client
         ["--" "clojure-lsp" "--default"])]

    (testing "Handshake"
      (let [resp (utils/request client "initialize"
                                {:capabilities client-capabilities
                                 :root-uri (str "file://" cwd)})]
        (is (= "Alligator (clojure-lsp)" (get-in resp [:result :server-info :name])))
        (utils/notify client "initialized")))

    (testing "Get diagnostics"
      (let [uri (str "file://" cwd "/test.clj")]
        (utils/notify client "textDocument/didOpen"
                      {:text-document
                       {:uri uri
                        :version 1
                        :language-id "clojure"
                        :text doc-content}})

        (let [msg (utils/await-notification client (fn [msg]
                                                     (and (= (:method msg) "textDocument/publishDiagnostics")
                                                          (= (get-in msg [:params :uri]) uri))))]
          (is (not (nil? msg)) "Timed out: did not receive notification from the server")
          (is (= (get-in msg [:params :uri]) uri))
          (let [diagnostics (get-in msg [:params :diagnostics])]
            (is (seq diagnostics))
            (is (some #(= (:code %) "namespace-name-mismatch")
                      diagnostics))))))

    (utils/stop-servers-and-client! test-objects)))
