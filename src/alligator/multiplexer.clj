(ns alligator.multiplexer
  "This module contains:

   1. management of LSP servers as subprocess (via the `start-server` function).
   2. server utility functions, e.g., getting the list of supported methods for each server."
  (:require
   [clojure.java.process :as proc]
   [clojure.core.async :as async]
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

;; collect all messages form all servers
;; {:message msg :from server-name}
(defonce server-output (async/chan 100))

;; This item holds all running servers
(defonce enabled-servers (atom []))

(defn start-server
  "Start a server subprocess and return a map with channels for communication.
  :stdin - channel to write messages TO the subprocess (its stdin)

  The server will read from its subprocess stdout and forward all messages
  to the server-output with metadata about which server sent it."
  ([name command] (start-server name command [] true))
  ([name command capabilities] (start-server name command capabilities false))
  ([name command capabilities is-default]
   (let [p (apply proc/start command)
        ;; subprocess's stdin -> we write to it
         stdin (-> p
                   proc/stdin
                   io/output-stream->output-chan)
         capabilities (distinct (concat capabilities [:*]))]

     ;; Read from subprocess stdout and forward to common channel
     (async/thread
       (let [proc-out-chan (-> p
                               proc/stdout
                               io/input-stream->input-chan)]
         (loop []
           (when-let [message (async/<!! proc-out-chan)]

            ;; Add server name to the message before forwarding
             (async/>!! server-output {:from name :message message})
             (recur)))))

     {:name name :proc p :stdin stdin :capabilities capabilities :is-default is-default})))

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

(defn server-accept-method
  "Return a list of servers who are configured to accept METHOD."
  [method]
  (filter #(request-supported? % method) @enabled-servers))

(defn configured-capabilities-from-server-name
  "Return the configured capabilities of a server by name.
  Return nil for default server.
  Throws an exception if server is not found."
  [name]
  (if-let [server (some #(when (= (:name %) name) %) @enabled-servers)]
    (when (not (:is-default server))
      (:capabilities server))
    (throw (ex-info (str "Server not found: " name) {:server-name name}))))

(defn is-default-server
  "Check if a server is the default server by name.
  Throws an exception if server is not found."
  [name]
  (if-let [server (some #(when (= (:name %) name) %) @enabled-servers)]
    (:is-default server)
    (throw (ex-info (str "Server not found: " name) {:server-name name}))))
