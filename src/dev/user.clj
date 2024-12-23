(ns user
  (:require [clj-reload.core :as clj-reload]
            [integrant.core :as ig]
            [nrepl.server :refer [default-handler start-server]]
            [personal-rss-feed.config :as config]
            [portal.api]
            [taoensso.timbre :as log]))

(clj-reload.core/init {:dirs ["src/dev" "src/main" "src/test"]})
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

  ;; (@shadow-start!)
  ;; (@shadow-watch :dev) ; depends on shadow server
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
  (portal.api/open {:window-title "LE RSS Server" #_#_:launcher :vs-code})
  (add-tap #'portal.api/submit))

(comment (tap> 1)
         (portal.api/docs))
