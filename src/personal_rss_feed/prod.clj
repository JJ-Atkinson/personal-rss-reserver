(ns personal-rss-feed.prod
  (:require
   [integrant.core :as ig]
   [nrepl.server :refer [start-server stop-server default-handler]]
   [personal-rss-feed.config]
   [taoensso.timbre :as log]))

(defonce system (atom {}))

(log/merge-config! 
  {:appenders {:spit (log/spit-appender {:fname "./timbre-split.log"})}})

(defn start-server!
  [& args]
  (defonce server (start-server :bind "0.0.0.0"
                    :port 8001
                    :handler (default-handler #'com.gfredericks.debug-repl/wrap-debug-repl)))
  (reset! system
    (ig/init (personal-rss-feed.config/resolve-config! true))))
