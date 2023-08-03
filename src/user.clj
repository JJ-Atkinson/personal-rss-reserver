(ns user
  (:require
   [nrepl.server :refer [start-server stop-server default-handler]]
   [com.gfredericks.debug-repl]))

(defn dev-main
  [& args]
  (defonce server (start-server :bind "0.0.0.0"
                    :port 8001
                    :handler (default-handler #'com.gfredericks.debug-repl/wrap-debug-repl)))
  (println "NREPL Server located at 8001")
  (Thread/sleep Long/MAX_VALUE))
