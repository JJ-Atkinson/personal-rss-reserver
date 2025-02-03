(ns ^:dev/always user ; Electric currently needs to rebuild everything when any file changes. Will fix
  (:require
   #?@(:clj
       [[integrant.core :as ig]
        [nrepl.server :refer [default-handler start-server]]
        [cider.nrepl.middleware]
        [personal-rss-feed.config :as config]
        [taoensso.timbre :as log]
        [taoensso.encore :as enc]
        [nrepl.middleware :as middleware]]
       :cljs
       [personal-rss-feed.admin.electric-app.main
        [hyperfiddle.electric3 :as e]
        hyperfiddle.electric-client3])))

#?(:clj
   (do

     (defmacro e->nil [form] `(try ~form (catch Exception e# nil)))

     ;; prevents compilation lockup for prod since clj-reload isn't on prod/build
     (when-let [clj-reload-init (e->nil (requiring-resolve 'clj-reload.core/init))]
       (clj-reload-init {:dirs ["src/dev" "src/main" "src/test"]}))

     (log/set-min-level! :debug)

     (def shadow-start! (delay @(requiring-resolve 'shadow.cljs.devtools.server/start!)))
     (def shadow-watch (delay @(requiring-resolve 'shadow.cljs.devtools.api/watch)))

     ^:clj-reload/keep
     (defonce !system (atom nil))


     (comment
       (tap> (#'config/resolve-config! false)))
     (defn start
       []
       (println "Starting system!")
       (reset! !system
         (ig/init (#'config/resolve-config! false)))

       (@shadow-start!)
       (@shadow-watch :dev) ; depends on shadow server
     )

     (defn stop
       []
       (ig/halt! @!system))

     (defn suspend
       []
       (ig/suspend! @!system))

     (defn resume
       []
       (ig/resume (#'config/resolve-config! false) @!system))

     (defn restart
       []
       (when @!system (stop))
       (start))

     (defn dev-main
       [& args]
       (require 'com.gfredericks.debug-repl)

       (let [middleware (->> (concat [
                                      'shadow.cljs.devtools.server.nrepl/middleware
                                      'com.gfredericks.debug-repl/wrap-debug-repl
                                      #_'jarrett.completions/wrap-completion ;; commented out until I need it
                                     ]
                                     cider.nrepl.middleware/cider-middleware)
                             ;; (remove #{'cider.nrepl/wrap-complete})
                             (map requiring-resolve)
                             (remove nil?))]
         (defonce server
           (start-server :bind    "0.0.0.0"
                         :port    8002
                         :handler (apply default-handler middleware)))
         (spit ".nrepl-port" "8002")
         (println "NREPL Server located at 8002")
         (println "Applied middleware:" middleware)
         (start)
         (Thread/sleep Long/MAX_VALUE)))

     (defn start-portal!
       []
       ; similar to clj reload, this prevents lockup on
       (when-let [portal-api-open (e->nil (requiring-resolve 'portal.api/open))]
         (portal-api-open {:window-title "LE RSS Server" #_#_:launcher :vs-code})
         (add-tap (requiring-resolve 'portal.api/submit))))

     (comment
       (tap> 1)
       (portal.api/docs))))


#?(:cljs
   (do
     (defonce reactor nil)

     (defn ^:dev/after-load ^:export start!
       []
       ;;  (assert (nil? reactor) "reactor already running")
       (set! reactor
             ((e/boot-client {}
                             personal-rss-feed.admin.electric-app.main/Main 
                             (e/server nil)
                             (e/server nil))
              #(js/console.log "Reactor success:" %)
              #(js/console.error "Reactor failure:" %)))
              #_
       (set! reactor
             ((e/boot-client
               {}
               personal-rss-feed.admin.electric-app.main/Main
               nil ;; config is nil on the client
               nil ;; ring request is nil
              )
              #(js/console.log "Reactor success:" %)
              #(js/console.error "Reactor failure:" %))))

     (defn ^:dev/before-load stop!
       []
       (when reactor (reactor)) ; teardown
       (set! reactor nil))))