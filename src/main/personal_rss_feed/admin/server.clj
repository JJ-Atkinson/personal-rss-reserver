(ns personal-rss-feed.admin.server
  (:require [clj-simple-router.core :as router]
            [clojure.string :as str]
            [integrant.core :as ig]
            [personal-rss-feed.admin.auth :as auth]
            [ring.adapter.jetty9 :as ring-jetty]
            [ring.middleware.head :as head]
            [personal-rss-feed.admin.electric-jetty :as electric-jetty]
            [hiccup.page :as page]
            [ring.middleware.defaults :as ring.defaults]
            [ring.util.response :as response]
            [taoensso.timbre :as log]))

(def safe-prefixes
  "Prefixes for URI that should ALWAYS be allowed"
  ["/login"
   "/favicon.ico"
   "/public"])

(defn logged-in-claims
  [{:keys [auth]} req]
  (when-let [jwt (get-in req [:cookies "authorization" :value])]
    (auth/jwt-claims auth jwt)))

(defn log-in!
  [req jwt]
  (-> req
    (response/set-cookie "authorization" jwt
      {:secure    true
       :http-only true
       :same-site :strict})))

(defn wrap-logged-in
  [handler config]
  (fn [req]
    (let [claims (logged-in-claims config req)]
      (if (or claims
            (some #(str/starts-with? (:uri req) %) safe-prefixes))
        (handler (assoc req :auth/claims claims))
        (response/redirect "/login")))))

(defn login-form
  [{:keys [error?]}]
  (-> (page/html5
        [:head
         [:title "Login"]
         [:script {:src "/public/tailwind-styles.js"}]]
        [:body {:class "flex justify-center items-center h-screen bg-gray-100"}
         [:div {:class "w-full max-w-xs"}
          [:form {:method "post" :action "/login" :class "bg-white shadow-md rounded px-8 pt-6 pb-8 mb-4"}
           [:div {:class "mb-4"}
            [:label {:for "username" :class "block text-gray-700 text-sm font-bold mb-2"} "Username"]
            [:input {:type "text" :name "username" :id "username" :class "shadow appearance-none border rounded w-full py-2 px-3 text-gray-700 leading-tight focus:outline-none focus:shadow-outline"}]]
           [:div {:class "mb-6"}
            [:label {:for "password" :class "block text-gray-700 text-sm font-bold mb-2"} "Password"]
            [:input {:type "password" :name "password" :id "password" :class "shadow appearance-none border rounded w-full py-2 px-3 text-gray-700 mb-3 leading-tight focus:outline-none focus:shadow-outline"}]]
           (when error?
             [:div {:class "mb-6"}
              [:span.text-red-700.mb-2.text-sm.font-bold "Username & Password do not exist"]])
           [:div {:class "flex items-center justify-between"}
            [:input {:type "submit" :value "Login" :class "bg-blue-500 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded focus:outline-none focus:shadow-outline"}]]]]])
    (response/response)
    (response/content-type "text/html")))

(defn login-post
  [config {{:strs [username password]} :form-params}]
  (if-let [jwt (auth/generate-jwt-from-credentials (:auth config) username password)]
    (log-in! (response/redirect "/") jwt)
    (login-form {:error? true})))

(defn routes
  [{:keys [db/conn feed/secret-path-segment feed/public-feed-address] :as config}]
  {"GET /"       (electric-jetty/index-page "public" "public/js/manifest.edn")
   "GET /login"  login-form
   "POST /login" (partial login-post config)
   "GET /public/**"
   (fn [{[path] :path-params :as req}]
     (or
       (-> (response/resource-response path {:root "/public"})
         (head/head-response req))
       (response/not-found "No resource found")))})


;;; SERVER COMPONENT ==============================

;; The indirection for `handler` and `init-key` enable tools.ns.refresh, without the need for suspend/resume. quite handy
(defn handler
  [config]
  (let [router       (memoize router/router)
        root-handler (fn [req]
                       (try
                         (or ((router (#'routes config)) req)
                           (response/not-found "Route not found"))
                         (catch Exception e (log/error "Handler error" e) (pr-str e))))]
    (-> root-handler
      (electric-jetty/electric-websocket-middleware)
      (wrap-logged-in config)
      (ring.defaults/wrap-defaults (assoc-in ring.defaults/site-defaults
                                     [:security :anti-forgery] false)))))

(defmethod ig/init-key ::server
  [_ {:keys [jetty] :as config}]
  (let [options (assoc jetty
                  :join? false
                  :configurator electric-jetty/add-gzip-handler)]
    (println "Starting server" options)
    (ring-jetty/run-jetty (handler config) options)))

(defmethod ig/suspend-key! ::server
  [_ _])

(defmethod ig/resume-key ::server
  [_ _ _ _])

(defmethod ig/halt-key! ::server
  [_ server]
  (ring-jetty/stop-server server))
