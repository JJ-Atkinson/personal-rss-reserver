(ns personal-rss-feed.admin.electric-app.pages.users
  (:require
   #?@(:clj [[personal-rss-feed.feed.db :as feed.db]
             [datalevin.core :as d]])
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-ui4 :as ui]
   [hyperfiddle.electric-dom2 :as dom]
   [personal-rss-feed.admin.electric-app.env :as e.env]
   [personal-rss-feed.admin.electric-app.components.basic-table :as basic-table]
  ))

(e/def columns
  [{::basic-table/key   :user/uname
    ::basic-table/title "Username"}
   {::basic-table/key   :user/admin?
    ::basic-table/title "Admin?"}])

(defn query-users
  [conn]
  #?(:clj
     (let
       [res
        (d/q '[:find
               (pull
                ?e
                [:user/uname
                 :user/admin?])
               :in $
               :where [?e :user/uname]]
             (d/db conn))]
       (map first res))))


(e/defn UserViewPage
  []
  (e/client
   (dom/h2 (dom/text "UVP"))
   (basic-table/Table. :user/uname columns (e/server (query-users (e.env/Conn.))))))

