(ns alligator.test-utils
  (:require
   [alligator.methods.execute-command :as exec-cmd]
   [alligator.multiplexer :as mux]
   [alligator.request-states :as states]
   [clojure.core.async :as async]))

(defn reset-all-states [f]
  (with-redefs [exec-cmd/server-commands-map (atom {})
                mux/enabled-servers (atom [])
                mux/server-output (async/chan 100)
                states/outstanding-client-requests (atom {})
                states/server-request-id-mapping (atom {})]
    (f)))
