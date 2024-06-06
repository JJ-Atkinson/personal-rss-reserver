(ns ^:dev/always user ; Electric currently needs to rebuild everything when any file changes. Will fix
  (:require
   personal-rss-feed.admin.electric-app.main
   hyperfiddle.electric
   hyperfiddle.electric-dom2))

(def electric-main
  (hyperfiddle.electric/boot-client 
   {}
   personal-rss-feed.admin.electric-app.main/Main
   nil))

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