(ns alligator.mock.diagnostics.diagnostics-test
  (:require
   [alligator.methods :as methods]
   [alligator.mock.diagnostics.s1 :as server-1]
   [alligator.mock.diagnostics.s2 :as server-2]
   [alligator.multiplexer :as mux]
   [alligator.test-utils :as utils]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]))

(methods/load-handlers!)

(def ^:private root-uri
  (format "file:/%s" (System/getProperty "user.dir")))

(def ^:private file-uri
  (format "%s/foo.clj" root-uri))

(defn ^:private test-diagnostics [notif-chan expected]
  (let [timeout-chan (async/timeout 5000)
        [msg chan] (async/alts!! [timeout-chan notif-chan])]
    (if (= chan timeout-chan)
      (is false "Timeout getting the diagnostics notification")
      (is (= (get-in msg [:params :diagnostics])
             expected)))))

(deftest diagnostics-integration-test
  (let [{:keys [client multiplexer]} (utils/start-servers-and-client
                                      ["--" "clj -M:test -m alligator.mock.diagnostics.s1" "--default"
                                       ;; alligator identifies servers using their executable, and two servers with the same name
                                       ;; will name collision
                                       "--" "clojure -M:test -m alligator.mock.diagnostics.s2" "-c" "diagnostic-provider"])
        notif-chan (:notification-chan client)]

    (testing "Successfully launch 2 servers"
      (is (= (count (mux/list-servers multiplexer)) 2)))

    (testing "Handshake"
      (let [resp (utils/request client "initialize" {:capabilities {} :root-uri "file:///"})]
        (is (= "Alligator (clj+clojure)" (get-in resp [:result :server-info :name])))
        (utils/notify client "initialized")))

    (Thread/sleep 500)
    (utils/notify client "textDocument/didOpen" {:text-document {:uri file-uri}})

    (testing "Receive the first diagnostic notification"
      (test-diagnostics notif-chan [server-1/new-diagnostics]))

    (testing "Receive the second diagnostic notification"
      (test-diagnostics notif-chan [server-1/new-diagnostics server-2/diagnostics1]))

    (testing "Receive the third diagnostic notification"
      (test-diagnostics notif-chan [server-1/new-diagnostics server-2/diagnostics2]))

    (utils/stop-servers-and-client! {:client client :multiplexer multiplexer})))
