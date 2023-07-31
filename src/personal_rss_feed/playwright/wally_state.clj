(ns personal-rss-feed.playwright.wally-state
  (:require [clojure.tools.namespace.repl :as clojure.repl])
  (:import
   (com.microsoft.playwright Playwright)))

;; Do not reload this file ever - it contains objects that need to be shut down with the JVM

(clojure.repl/disable-reload!)

(def playwright-instance
  "The global playwright object, closed when the JVM exits."
  (delay (Playwright/create)))

(.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn []
                                                            (when (realized? playwright-instance)
                                                              (.close @playwright-instance)))))

(def name->playwright-object
  "Named playwright objects that are maintained across tests"
  (atom {}))

