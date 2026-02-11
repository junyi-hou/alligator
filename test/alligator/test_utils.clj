(ns alligator.test-utils
  (:require
   [alligator.methods.diagnostics :as diag]
   [alligator.methods.execute-command :as exec-cmd]
   [alligator.multiplexer :as mux]
   [alligator.states :as states]
   [clojure.core.async :as async]))

(defn reset-all-states [f]
  (with-redefs [diag/diagnostics-cache (atom {})
                exec-cmd/server-commands-map (atom {})
                mux/enabled-servers (atom [])
                mux/server-output (async/chan 100)
                states/exit-chan (async/chan 1)
                states/outstanding-client-requests (atom {})
                states/server-request-id-mapping (atom {})]
    (f)))
