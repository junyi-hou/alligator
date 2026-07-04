(ns alligator.mock.basic.basic-test
  (:require
   [alligator.methods :as methods]
   [alligator.mock.basic.server :as server]
   [alligator.test-utils :as utils]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]))

(methods/load-handlers!)

(deftest ^:mock basic-integration-test
  (let [{:keys [client] :as test-objects} (utils/start-servers-and-client ["--" "clojure -M:test -m alligator.mock.basic.server" "--default"])]

    ;; 1. Initialize Handshake
    (testing "Handshake"
      (let [resp (utils/request client "initialize" {:capabilities {} :root-uri "file:///"})]
        (is (= "Alligator (clojure)" (get-in resp [:result :server-info :name])))
        (utils/notify client "initialized")))

    ;; 2. Completion Request
    (testing "Request and respond"
      (let [resp (utils/request client "textDocument/completion" {})]
        (is (= server/completion-items (:result resp)))))

    ;; 3. Diagnostics Notification
    (testing "Notification"
      (let [timeout-chan (async/timeout 1000)
            [notif channel] (async/alts!! [(:notification-chan client) timeout-chan])]
        (when (= channel timeout-chan)
          (is false "Timed out: did not receive notification from the server"))
        (is (get-in notif [:params :diagnostics]) server/diagnostics-item)
        (is (get-in notif [:params :uri]) "file:///test.clj")))

    (utils/stop-servers-and-client! test-objects)))
