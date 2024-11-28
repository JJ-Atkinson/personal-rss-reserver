(ns personal-rss-feed.admin.electric-app.env 
  (:require [hyperfiddle.electric :as e]))

(e/def ring-request)
(e/def config)

(e/defn Conn [] (:db/conn config))
