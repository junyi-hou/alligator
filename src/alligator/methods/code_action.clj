(ns alligator.methods.code-action
  (:require
   [alligator.methods :as methods]
   [alligator.multiplexer :as mux]
   [clojure.core.async :as async]))

(defn ^:private interject-code-action-source
  "When applying the selected code action, client may need to send it back to the server
   to resolve it. In this case, we need to know which server is responsible for the sent
   back code action and relay the code action result to the correct server.

   To do so, we interject a custom identifier to the `:data` field of the
   codeActionItem. For the legacy command codeActions, use the `execute-command-provider`
   in the server initialize response to identify the server to send the subsequent
   execute-command request."
  [result server-name]
  (letfn [(interject [item]
            (if (:kind item)
              ;; codeActionItem (has :kind field)
              (assoc-in item [:data :alligator-source] server-name)
              ;; fallback - does nothing
              item))]
    (->> result
         (map interject)
         vec)))

(defmethod methods/process-server-message "textDocument/codeAction"
  [_ code-action-chan output-chan multiplexer]
  (async/go-loop [inflight {}]
    (when-let [msg (async/<! code-action-chan)]
      (let [{:keys [message] server-name :from} msg
            id (:id message)
            result (interject-code-action-source (:result message) server-name)
            entry (or (get inflight id)
                      {:results {}
                       :expected-count (count (mux/server-accept-method multiplexer "textDocument/codeAction"))})
            new-results (assoc (:results entry) server-name result)]
        (if (>= (count new-results) (:expected-count entry))
          (do
            (async/>! output-chan
                      {:jsonrpc "2.0"
                       :id id
                       :result (vec (mapcat identity (vals new-results)))})
            (recur (dissoc inflight id)))
          (recur (assoc inflight id (assoc entry :results new-results))))))))

(defmethod methods/process-client-message "codeAction/resolve"
  [_ resolve-chan multiplexer]
  (async/go-loop []
    (when-let [message (async/<! resolve-chan)]
      (let [params (:params message)
            server-name (get-in params [:data :alligator-source])]

        (if-let [server (mux/get-server-by-name multiplexer server-name)]
          (async/>!
           (:stdin server)
           ;; remove the added :alligator-source key
           (assoc-in message [:params :data] (dissoc (get params :data) :alligator-source)))

          ;; if no server-name is found or no running server with the name, return an error
          (async/>! (mux/get-output-chan multiplexer)
                    {:jsonrpc "2.0"
                     :id (:id message)
                     :error {:code -32000
                             :message "Could not find the right server to relay the codeAction/Resolve request"
                             :data (:data params)}}))))

    (recur)))
