(ns user
  (:require
   [integrant.core :as ig]
   [nrepl.server :refer [start-server stop-server default-handler]]
   [clojure.tools.namespace.repl :as tools.namespace]
   [personal-rss-feed.config :as config]
   [com.gfredericks.debug-repl]))

(tools.namespace/set-refresh-dirs "src")
(tools.namespace/disable-reload!)

(defonce system (atom nil))

(defn start
  []
  (reset! system
    (ig/init (#'config/resolve-config! false))))

(defn stop
  []
  (ig/halt! @system))

(defn suspend
  []
  (ig/suspend! @system))

(defn resume
  []
  (ig/resume (#'config/resolve-config! false) @system))

(defn restart 
  []
  (when @system (stop))
  (start))

(defn dev-main
  [& args]
  (defonce server (start-server :bind "0.0.0.0"
                    :port 8001
                    :handler (default-handler #'com.gfredericks.debug-repl/wrap-debug-repl)))
  (println "NREPL Server located at 8001")
  (Thread/sleep Long/MAX_VALUE)
  (start))
