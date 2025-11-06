(ns personal-rss-feed.admin.routes
  (:require
   [clojure.string :as str]
   [personal-rss-feed.admin.pages.login :as p.login]
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
  {"GET /login" p.login/login-form
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

(defn wrap-admin
  [config handler] ;; handler == all routes
  (->
    handler
    (wrap-logged-in config)
    (rm.content-type/wrap-content-type)
    (ring.defaults/wrap-defaults (assoc-in ring.defaults/site-defaults
                                  [:security :anti-forgery]
                                  false))))
