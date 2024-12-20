(ns personal-rss-feed.admin.pages.login
  (:require
   [personal-rss-feed.admin.auth :as auth]
   [hiccup.page :as page]
   [ring.util.response :as response]))


(defn logged-in-claims
  [{:keys [auth]} req]
  (when-let [jwt (get-in req [:cookies "authorization" :value])]
    (auth/jwt-claims auth jwt)))

(defn log-in!
  [req jwt]
  (-> req
      (response/set-cookie "authorization"
                           jwt
                           {:secure    true
                            :http-only true
                            :same-site :strict})))

(defn login-form
  [{:keys [error?]}]
  (->
    (page/html5
     [:head
      [:title "Login"]
      [:script {:src "/public/tailwind-styles.js"}]]
     [:body {:class "flex justify-center items-center h-screen bg-gray-100"}
      [:div {:class "w-full max-w-xs"}
       [:form {:method "post" :action "/login" :class "bg-white shadow-md rounded px-8 pt-6 pb-8 mb-4"}
        [:div {:class "mb-4"}
         [:label {:for "username" :class "block text-gray-700 text-sm font-bold mb-2"} "Username"]
         [:input
          {:type "text"
           :name "username"
           :id "username"
           :class
           "shadow appearance-none border rounded w-full py-2 px-3 text-gray-700 leading-tight focus:outline-none focus:shadow-outline"}]]
        [:div {:class "mb-6"}
         [:label {:for "password" :class "block text-gray-700 text-sm font-bold mb-2"} "Password"]
         [:input
          {:type "password"
           :name "password"
           :id "password"
           :class
           "shadow appearance-none border rounded w-full py-2 px-3 text-gray-700 mb-3 leading-tight focus:outline-none focus:shadow-outline"}]]
        (when error?
          [:div {:class "mb-6"}
           [:span.text-red-700.mb-2.text-sm.font-bold "Username & Password do not exist"]])
        [:div {:class "flex items-center justify-between"}
         [:input
          {:type "submit"
           :value "Login"
           :class
           "bg-blue-500 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded focus:outline-none focus:shadow-outline"}]]]]])
    (response/response)
    (response/content-type "text/html")))

(defn login-post
  [config {{:strs [username password] :as fp} :form-params}]
  (try
    (if-let [jwt (auth/generate-jwt-from-credentials (:auth config) username password)]
      (log-in! (response/redirect "/") jwt)
      (login-form {:error? true}))
    (catch Exception e
      (login-form {:error? true}))))
