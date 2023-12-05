(ns personal-rss-feed.prod
  (:require
   [integrant.core :as ig]
   [nrepl.server :refer [start-server stop-server default-handler]]
   [personal-rss-feed.config]
   [taoensso.timbre :as log])
  (:gen-class))

(defonce system (atom {}))

(log/merge-config! 
  {:appenders {:spit {:enabled? false}}})

(defn start-server!
  [& args]
  (defonce server (start-server :bind "0.0.0.0"
                    :port 8001))
  (println "Repl started at port 8001")
  (reset! system
    (ig/init (personal-rss-feed.config/resolve-config! true))))

(defn -main 
  [& args]
  (start-server!))

(comment
  (reset! system
    (ig/init (personal-rss-feed.config/resolve-config! true))))