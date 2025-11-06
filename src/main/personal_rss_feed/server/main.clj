(ns personal-rss-feed.server.main
  (:require
   [clj-simple-router.core :as router]
   [clojure.set :as set]
   [integrant.core :as ig]
   [personal-rss-feed.admin.routes :as admin.routes]
   [personal-rss-feed.feed.routes :as feed.routes]
   [ring.adapter.jetty :as ring]
   [ring.util.response :as response]
   [taoensso.timbre :as log]))

;; The indirection for `handler` and `init-key` enable tools.ns.refresh, without the need for suspend/resume. quite
;; handy

(defn create-routes
  [config]
  (let [routes [(#'feed.routes/routes config)
                (#'admin.routes/routes config)]]
    (reduce
     (fn [r1 r2]
       (if-let [similar-routes (seq (set/intersection (set (keys r1)) (set (keys r2))))]
         (throw (ex-info "Overlapping routes!" {:routes similar-routes}))
         (merge r1 r2)))
     {}
     routes)))

(defn update-config
  [config]
  (assoc config
         ::admin.routes/safe-prefixes
         (concat (feed.routes/safe-prefixes config)
                 (admin.routes/safe-prefixes config))))

(defn handler
  [config]
  (let [create-router (memoize router/router)]
    (fn [req]
      (let [router  (create-router (#'create-routes config))
            handler (admin.routes/wrap-admin (update-config config) router)]
        (try
          (or (handler req)
              (response/not-found "Route not found"))
          (catch Exception e (log/error "Handler error" e)))))))

(defmethod ig/init-key ::server
  [_ {:keys [jetty] :as config}]
  (let [options (merge {:port  3001
                        :host  "0.0.0.0"
                        :join? false}
                       jetty)]
    (println "Starting server " options)
    (ring/run-jetty (handler config)
                    options)))

(defmethod ig/suspend-key! ::server
  [_ _])

(defmethod ig/resume-key ::server
  [_ _ _ _])

(defmethod ig/halt-key! ::server
  [_ server]
  (.stop server))
