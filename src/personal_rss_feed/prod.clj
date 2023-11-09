(ns personal-rss-feed.prod
  (:require
   [integrant.core :as ig]
   [personal-rss-feed.config]))

(defonce system (atom {}))

(defn start-server!
  []
  (reset! system
    (ig/init (personal-rss-feed.config/resolve-config! true))))
