(ns personal-rss-feed.admin.routes
  (:require
   [clojure.string :as str]
   [hyperfiddle.electric3 :as e]
   personal-rss-feed.admin.electric-app.main
   [personal-rss-feed.admin.electric-httpkit-adapter-fork :as hk-electric]
   [personal-rss-feed.admin.pages.login :as p.login]
   [personal-rss-feed.admin.pages.electric-index :as p.electric-index]
   [ring.middleware.content-type :as rm.content-type]
   [ring.middleware.defaults :as ring.defaults]
   [ring.middleware.head :as head]
   [ring.util.response :as response]))

(defn safe-prefixes
  "Prefixes for URI that should ALWAYS be allowed"
  [_config]
  ["/favicon.ico"
   "/public"])

(defn routes
  [{:keys [db/conn feed/secret-path-segment feed/public-feed-address] :as config}]
  {"GET /**" (fn [req] (p.electric-index/handle-index config req))
   "GET /login" p.login/login-form
   "POST /login" (partial p.login/login-post config)
   "GET /public/**"
   (fn [{[path] :path-params :as req}]
     (or
      (-> (response/resource-response path {:root "/public"})
          (head/head-response req))
      (response/not-found "No resource found")))})

(defn wrap-logged-in
  [handler {::keys [safe-prefixes] :as config}]
  (fn [req]
    (let [claims       (p.login/logged-in-claims config req)
          safe-prefix? (some #(str/starts-with? (:uri req) %)
                             (conj safe-prefixes
                                   "/login"))] ;; make sure we don't kill things that are logins
      (if (or claims safe-prefix?)
        (handler (assoc req :auth/claims claims))
        (response/redirect "/login")))))

(defn boot-electric
  [config ring-request]
  (tap> [:boot config ring-request])
  (e/boot-server {}
                 personal-rss-feed.admin.electric-app.main/Main
                 (e/server config)
                 (e/server ring-request)))

(defn wrap-electric
  [config handler] ;; handler == all routes
  (->
    ;; No need for 'not-found, since the behavior of the global router is not-found if nil
    handler ;; Lowest priority
    ;; Index page handled in routes
    (hk-electric/wrap-electric-websocket (partial boot-electric config))
    (wrap-logged-in config) ;; Hides routes by default
    ;; No need for 'wrap-resources, since the `GET /public/**` takes care of that
    (rm.content-type/wrap-content-type)
    (ring.defaults/wrap-defaults (assoc-in ring.defaults/site-defaults
                                  [:security :anti-forgery]
                                  false))))
