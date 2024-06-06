(ns ^:dev/always user ; Electric currently needs to rebuild everything when any file changes. Will fix
  (:require
   personal-rss-feed.admin.app
   personal-rss-feed.admin.ui.recursive-viewer
   hyperfiddle.electric
   hyperfiddle.electric-dom2))

(def electric-main
  (hyperfiddle.electric/boot ; Electric macroexpansion - Clojure to signals compiler
   (binding [hyperfiddle.electric-dom2/node js/document.body]
     (personal-rss-feed.admin.ui.recursive-viewer/Viewer.))))

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