(ns personal-rss-feed.admin.electric-app.components.zprint
  (:require
   [zprint.core :as zp]))

(zp/configure-all! {:search-config? true})

(defn pprint-str
  ([clj]
   (zp/zprint-str clj))
  ([clj cfg]
   (zp/zprint-str clj cfg)))
