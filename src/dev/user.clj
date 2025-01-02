(ns user
  (:require
   [integrant.core :as ig]
   [nrepl.server :refer [default-handler start-server]]
   [personal-rss-feed.config :as config]
   [taoensso.timbre :as log]
   [taoensso.encore :as enc]))

;; prevents compilation lockup for prod since clj-reload isn't on prod/build
(when-let [clj-reload-init (requiring-resolve 'clj-reload.core/init)]
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
  (defonce server
    (start-server :bind    "0.0.0.0"
                  :port    8002
                  :handler (default-handler (requiring-resolve 'com.gfredericks.debug-repl/wrap-debug-repl))))
  (spit ".nrepl-port" "8002")
  (println "NREPL Server located at 8002")
  (start)
  (Thread/sleep Long/MAX_VALUE))

(defn start-portal!
  []
  ; similar to clj reload, this prevents lockup on
  (when-let [portal-api-open (requiring-resolve 'portal.api/open)]
    (portal-api-open {:window-title "LE RSS Server" #_#_:launcher :vs-code})
    (add-tap #'portal.api/submit)))

(comment
  (tap> 1)
  (portal.api/docs))
