(ns ingestion-api.core
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]])
  (:require [org.httpkit.server :refer [run-server]])
  (:require [clojure.java.io :refer [resource]])
  (:require [taoensso.timbre :as log])
  (:require [clojure.java.io :as io])
  (:require [ring.middleware.reload :as reload])
  (:require [ingestion-api.route :refer [app]]
            [ingestion-api.events :refer [*backend*]])
  (:require [ingestion-api.backend :refer [make-console-backend]]
            [ingestion-api.backend-kafka :refer [make-kafka-backend]])
  (:gen-class))


(def DEFAULT-CONFIG
  "Default configuration which will be merged with
  the user defined configuration."
  {:server {:port 9000 :auto-reload false}

   :log   {:timestamp-pattern "yyyy-MM-dd HH:mm:ss.SSS zzz"}

   :backend  {:type :console :pretty? true}
   ;:backend {:type :kafka :topic "events" :metadata.broker.list "192.168.59.103:9092"}
   })

(def cli-options
  "Command line options"
  [
   ["-c" "--config FILE" "Config file (.edn)"]
   ["-h" "--help"]
   ])


(defn message
  "Prints a message in the stderr channel"
  [& m]
  (binding [*out* *err*]
    (apply println m)))


(defn version
  "Reads the version from the project.clj file
  which is typically boundled with jar file"
  []
  (try
    (->> "project.clj"
         resource
         slurp
         read-string
         nnext
         first
         (str "v"))
    (catch Exception x "")))


(defn- headline
  "Return a string with the headline and the version number"
  []
  (format
   "
-----------------------------------------------
   _____
  / ___/____ _____ ___  _________ __________ _
  \\__ \\/ __ `/ __ `__ \\/ ___/ __ `/ ___/ __ `/
 ___/ / /_/ / / / / / (__  ) /_/ / /  / /_/ /
/____/\\__,_/_/ /_/ /_/____/\\__,_/_/   \\__,_/

  Samsara Ingestion API
-----------------------------| %7.7s |-------

" (version)))


(defn- help!
  "Pritns a help message"
  [errors]
  (let [err-msg (if-not errors "" (clojure.string/join "\n" errors))]
    (message
     (format
      "%s%s

SYNOPSIS
       ./ingestion-api -c config.edn

DESCRIPTION
       Starts a REST interface which accepts events
       and sends them to Samsara's processing pipeline.

  OPTIONS:

  -c --config config-file.edn [REQUIRED]
       Configuration file to set up the REST interface
       example: './config/config.edn'

  -h --help
       this help page

" (headline) err-msg))))


(defn exit!
  "System exit with error state"
  [n]
  (System/exit n))


(defn- read-config
  "Read the user's configuration file
  and apply the default values."
  [config-file]
  (->> (io/file config-file)
      slurp
      read-string
      (merge-with merge DEFAULT-CONFIG)))


(defn- init-log!
  "Initializes log settings"
  [cfg]
  (log/set-config! [:fmt-output-fn]
                   (fn [{:keys [level throwable message timestamp hostname ns]}
                       ;; Any extra appender-specific opts:
                       & [{:keys [nofonts?] :as appender-fmt-output-opts}]]
                     ;; <timestamp> <hostname> <LEVEL> [<ns>] - <message> <throwable>
                     (format "%s %s [%s] - %s%s"
                             timestamp (-> level name clojure.string/upper-case) ns (or message "")
                             (or (log/stacktrace throwable "\n" (when nofonts? {})) ""))))
(log/merge-config! cfg))


(defn- init-backend!
  "Create the specified backend where to send the events"
  [{:keys [type] :as cfg}]
  (reset! *backend*
          (case type
            :console (make-console-backend cfg)
            :kafka   (make-kafka-backend   cfg)
            (throw (RuntimeException. "Illegal backed type:" type)))))


(defn init!
  "Initializes the system and returns the actual configuration used."
  [config-file]
  (let [config (read-config config-file)]

    (init-log!     (-> config :log))
    (init-backend! (-> config :backend))

    config))


(defn -main
 [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
     (:help options)            (do (help! nil) (exit! 1))
     (nil? (:config options))   (do (help! ["ERROR: Missing configuration."]) (exit! 1))
     errors                     (do (help! errors) (exit! 1))

     :default
     (let [{config-file :config} options
           {{:keys [port auto-reload] :as server} :server :as config} (init! config-file)]
       (println (headline))
       ;; starting server
       (run-server (if auto-reload (reload/wrap-reload #'app) app) server)
       (log/info "Samsara Ingestion-API listening on port: " port)
       ;; warn when auto-reload is enabled
       (when auto-reload
             (log/warn "AUTO-RELOAD enabled!!! I hope you are in dev mode."))))))
