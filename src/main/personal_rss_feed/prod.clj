(ns personal-rss-feed.prod
  (:require
   [cider.nrepl.middleware]
   [clojure.pprint :as pprint]
   [integrant.core :as ig]
   [nrepl.server :refer [default-handler start-server]]
   [personal-rss-feed.config]
   [taoensso.timbre :as log])
  (:gen-class))

(defonce system (atom {}))

(log/merge-config!
 {:appenders {:spit {:enabled? false}}})

(defn start-server!
  [& args]
  (defonce server
    (start-server :bind    "0.0.0.0"
                  :port    8001
                  :handler (default-handler
                            (->> cider.nrepl.middleware/cider-middleware
                                 (map requiring-resolve)
                                 (remove nil?)))))
  (println "Repl started at port 8001")
  (reset! system
    (ig/init (personal-rss-feed.config/resolve-config! true))))

(pprint/pprint @system)

(defn -main
  [& args]
  (start-server!))

(comment
  (reset! system
    (ig/init (personal-rss-feed.config/resolve-config! true))))