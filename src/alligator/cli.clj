(ns alligator.cli
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [taoensso.timbre :as timbre]
   [toml-clj.core :as toml]))

(def usage
  (str/join "\n"
            ["Usage: alligator [options] [[--] \"server-command\" [server-options] ...]"
             ""
             "Options:"
             "  -c, --config CONFIG_FILE  Path to the config.toml file. (Default: config.toml)"
             "  --debug                   Enable debug logging to stderr."
             "  -h, --help                Show this help message."
             ""
             "Server Options (following \"--\"):"
             "  -c, --capabilities CAP    LSP capability to enable for this server (repeatable)."
             "  -d, --default             Set this server as the default server."
             ""
             "Example:"
             "  alligator -- \"pyright-langserver --stdio\" --default -- \"ruff server\" -c definition-provider"]))

(def alligator-cli-options
  [["-c" "--config CONFIG_FILE"
    "Path to the config.toml file. Will be ignored if servers are provided on the command line."
    :default "config.toml"]
   [nil "--debug" "Whether to print debug output to stderr"
    :id :debug
    :default false]
   ["-h" "--help"]])

(def server-options
  [["-c" "--capabilities CAPABILITIES" "A list of capabilities to use for the server"
    :multi true
    :default []
    :id :capabilities
    :update-fn (fnil conj [])]
   ["-d" "--default" "Whether to set this server as the default server"
    :default false
    :id :default
    :parse-fn boolean]])

(defn get-server-config [options arguments]
  (if (seq arguments)
    (loop [config {}
           args arguments]
      (if (seq args)
        (let [[this-server-config other-configs]
              (split-with #(not= % "--") (if (= "--" (first args)) (rest args) args))
              {:keys [options errors arguments]} (cli/parse-opts this-server-config server-options)
              command (str/split (first arguments) #" ")]

          (if errors
            (do (timbre/fatal usage)
                (System/exit 1))

            (recur (assoc config
                          (first command)
                          {"command" command
                           "capabilities" (:capabilities options)
                           "is_default" (:default options)})
                   other-configs)))
        config))
    (with-open [rdr (io/reader (:config options))]
      (toml/read rdr))))
