(ns alligator.multiplexer
  "This module contains:

   1. management of LSP servers as subprocess (via the `start-server` function).
   2. server utility functions, e.g., getting the list of supported methods for each server."
  (:require
   [clojure.core.async :as async]
   [clojure.java.process :as proc]
   [jsonrpc4clj.io-chan :as io]))

(def ^:private provider-methods
  "A mapping between each server capability and the client requests that it can handles"
  {;; requests
   :* ["initialize" "shutdown"]
   ;; :textDocumentSync.willSaveWaitUntil ["textDocument/willSaveWaitUntil"]
   :declaration-provider ["textDocument/declaration"]
   :definition-provider ["textDocument/definition"]
   :type-definition-provider ["textDocument/typeDefinition"]
   :implementation-provider ["textDocument/implementation"]
   :references-provider ["textDocument/references"]
   :call-hierarchy-provider ["textDocument/prepareCallHierarchy" "callHierarchy/incomingCalls" "callHierarchy/outgoingCalls"]
   :type-hierarchy-provider ["textDocument/prepareTypeHierarchy"]
   :document-highlight-provider ["textDocument/documentHighlight"]
   :document-link-provider ["textDocument/documentLink" "documentLink/resolve"]
   :hover-provider ["textDocument/hover"]
   :code-lens-provider ["textDocument/codeLens" "codeLens/resolve"]
   :folding-range-provider ["textDocument/foldingRange"]
   :selection-range-provider ["textDocument/selectionRange"]
   :document-symbol-provider ["textDocument/documentSymbol"]
   :semantic-tokens-provider ["textDocument/semanticTokens/full" "textDocument/semanticTokens/range" "textDocument/semanticTokens/delta"]
   :inlay-hint-provider ["textDocument/inlayHint" "inlayHint/resolve"]
   :inline-value-provider ["textDocument/inlineValue"]
   :moniker-provider ["textDocument/moniker"]
   :completion-provider ["textDocument/completion" "completionItem/resolve"]
   :diagnostic-provider ["textDocument/diagnostic" "workspace/diagnostic"]
   :signature-help-provider ["textDocument/signatureHelp"]
   :code-action-provider ["textDocument/codeAction" "codeAction/resolve"]
   :color-provider ["textDocument/documentColor" "textDocument/colorPresentation"]
   :document-formatting-provider ["textDocument/formatting"]
   :document-range-formatting-provider ["textDocument/rangeFormatting"]
   :document-on-type-formatting-provider ["textDocument/onTypeFormatting"]
   :rename-provider ["textDocument/rename" "textDocument/prepareRename"]
   :linked-editing-range-provider ["textDocument/linkedEditingRange"]
   :workspace-symbol-provider ["workspace/symbol" "workspaceSymbol/resolve"]
   ;; :workspace.workspaceFolders ["workspace/workspaceFolders"]
   :execute-command-provider ["workspace/executeCommand"]})

(defn ^:private get-supported-requests
  "Get all methods supported by the given capabilities"
  [capabilities]
  (distinct (mapcat #(get provider-methods %) capabilities)))

(defn ^:private request-supported?
  "Check if a method is supported by the server's capabilities"
  [server method]
  (if (:is-default server)
    (some #{method} (mapcat (fn [[_ v]] v) provider-methods))
    (let [supported-requests (get-supported-requests (:capabilities server))]
      (some #{method} supported-requests))))

(defprotocol IMultiplexer
  (_add-server! [this name command capabilities is-default])
  (list-servers [this])
  (servers-for-method [this method])
  (get-output-chan [this])
  (stop-all-servers! [this])
  (get-server-by-name [this name])
  (add-server-commands! [this server-name commands])
  (get-servers-for-command [this command])
  (server-accept-method [this method]))

(defrecord Multiplexer [server-output enabled-servers server-commands-map]
  IMultiplexer
  (_add-server! [_ name command capabilities is-default]
    (let [p (apply proc/start command)
          stdin (-> p proc/stdin io/output-stream->output-chan)
          caps (distinct (concat capabilities [:*]))
          server {:name name :proc p :stdin stdin :capabilities caps :is-default is-default}]

      ;; Read from subprocess stdout and forward to common channel
      (async/thread
        (let [proc-out-chan (-> p proc/stdout io/input-stream->input-chan)]
          (loop []
            (when-let [message (async/<!! proc-out-chan)]
              (async/>!! server-output {:from name :message message})
              (recur)))))

      (swap! enabled-servers conj server)
      server))

  (list-servers [_]
    @enabled-servers)

  (servers-for-method [_ method]
    (filter #(request-supported? % method) @enabled-servers))

  (get-output-chan [_]
    server-output)

  (get-server-by-name [_ name]
    (some #(when (= (:name %) name) %) @enabled-servers))

  (stop-all-servers! [_]
    (doseq [server @enabled-servers]
      (.destroy (:proc server))
      (async/close! (:stdin server))))

  (add-server-commands! [_ server-name commands]
    (swap! server-commands-map assoc server-name commands))

  (get-servers-for-command [_ command]
    (->> @server-commands-map
         (filter (fn [[_k v]] (some #{command} v)))
         (map first)))

  (server-accept-method [this method]
    (servers-for-method this method)))

(defn add-server!
  ([multiplier name command] (_add-server! multiplier name command [] true))
  ([multiplier name command capabilities] (_add-server! multiplier name command capabilities false))
  ([multiplier name command capabilities is-default] (_add-server! multiplier name command capabilities is-default)))

(defn create-multiplexer
  ([] (create-multiplexer (async/chan)))
  ([output-chan]
   (->Multiplexer output-chan (atom []) (atom {}))))

(defn configured-capabilities-from-server-name
  "Return the configured capabilities of a server by name.
   Return nil for default server.
   Throws an exception if server is not found."
  [mux name]
  (if-let [server (get-server-by-name mux name)]
    (when (not (:is-default server))
      (:capabilities server))
    (throw (ex-info (str "Server not found: " name) {:server-name name}))))

(defn is-default-server
  "Check if a server is the default server by name.
  Throws an exception if server is not found."
  [mux name]
  (if-let [server (get-server-by-name mux name)]
    (:is-default server)
    (throw (ex-info (str "Server not found: " name) {:server-name name}))))
