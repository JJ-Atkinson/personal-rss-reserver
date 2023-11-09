(ns personal-rss-feed.queue
  (:require
   [integrant.core :as ig]
   [dev.freeformsoftware.simple-queue.core :as simple-queue]))

(defmethod ig/init-key ::queue  [_ opts]
  (simple-queue/create-system! opts))

(defmethod ig/suspend-key! ::queue
  [_ _])

(defmethod ig/resume-key ::queue
  [_ _ _ _])

(defmethod ig/halt-key! ::queue
  [_ env]
  (simple-queue/close-system! env))
