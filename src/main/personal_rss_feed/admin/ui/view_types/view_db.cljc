(ns personal-rss-feed.admin.ui.view-types.view-db)

(defonce !id->view-fn
  #?(:cljs (atom {})
     :clj nil))
