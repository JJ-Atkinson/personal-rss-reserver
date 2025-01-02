(ns ^:dev/always user ; Electric currently needs to rebuild everything when any file changes. Will fix
  (:require
   personal-rss-feed.admin.electric-app.main
   [hyperfiddle.electric3 :as e]
   [hyperfiddle.electric-dom3 :as dom]
   [hyperfiddle.electric-client3]))

(def electric-main
  (e/boot-client
   {}
   personal-rss-feed.admin.electric-app.main/Main
   nil ;; config is nil on the client
   nil ;; ring request is nil
  ))

(defonce reactor nil)

(defn ^:dev/after-load ^:export start!
  []
  (assert (nil? reactor) "reactor already running")
  (set! reactor
        (electric-main
         #(js/console.log "Reactor success:" %)
         #(js/console.error "Reactor failure:" %))))

(defn ^:dev/before-load stop!
  []
  (when reactor (reactor)) ; teardown
  (set! reactor nil))