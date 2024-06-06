(ns personal-rss-feed.queue
  (:require
   [clojure.tools.namespace.repl :as tools.namespace]
   [integrant.core :as ig]
   [dev.freeformsoftware.simple-queue.core :as simple-queue]
   [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
   [taoensso.timbre :as log]))


^:clj-reload/keep
(defonce !system (atom nil))

(defmethod ig/init-key ::queue
  [_ opts]
  (let [system (simple-queue/create-system!
                (assoc opts
                       ::simple-queue/default-notify-timed-out!
                       #(log/warn "Task timed out! :id " (::queue-item/id %) %)))]
    (reset! !system system)
    system))

(defmethod ig/suspend-key! ::queue
  [_ _])

(defmethod ig/resume-key ::queue
  [_ _ _ _])

(defmethod ig/halt-key! ::queue
  [_ env]
  (simple-queue/close-system! env))


(comment
  @!system)