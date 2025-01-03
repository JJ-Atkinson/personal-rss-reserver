(ns personal-rss-feed.time-test
  (:require
   [dev.freeformsoftware.simple-queue.core :as simple-queue]
   [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
   [personal-rss-feed.time-utils :as time-utils]
   [personal-rss-feed.ingest.lotus-eaters.fetch-metadata :as le.fetch-metadata]
   [fulcro-spec.core :refer [specification provided! when-mocking! assertions behavior when-mocking component =>]])
  (:import (java.time Duration Instant)
           (java.util Date)))


(.minus (Instant/now) (Duration/ofDays 1))

(defn qi
  [duration-past]
  (let [time (Date/from (.minus (Instant/now) duration-past))]
    {::queue-item/id              (random-uuid)
     ::queue-item/activation-time time
     ::queue-item/submission-time time}))

(specification
 "fetch-metadata-rate-limit"
 (component
  "rate-limit-number-active"
  (let [limit?-two (simple-queue/rate-limit-number-active 2)]
    (assertions
     "Only two can be active before the lockout is active"
     (limit?-two nil [1] nil)
     =>
     false
     (limit?-two nil [1 2] nil)
     =>
     true
     (limit?-two nil [1 2 3] nil)
     =>
     true)))

 (component
  "rate-limit-x-period"
  (let [limit?-two-per-day (time-utils/queue-rate-limit-x-per-period
                            {:period-s    (* 60 60 24)
                             :limit-count 2})
        within-limit       (qi (Duration/ofHours 12))
        outside-limit      (qi (Duration/ofHours 36))
        blank              {}]
    (assertions
     "Don't lock when fewer than the limit have been processed recently"
     (limit?-two-per-day [blank] [within-limit] [outside-limit])
     =>
     false
     (limit?-two-per-day [blank] [] [within-limit outside-limit])
     =>
     false

     "Lock when = number have been processed recently"
     (limit?-two-per-day [blank] [within-limit] [within-limit outside-limit])
     =>
     true
     (limit?-two-per-day [blank] [within-limit within-limit] [outside-limit])
     =>
     true
     (limit?-two-per-day [blank] [] [within-limit within-limit outside-limit])
     =>
     true)))

 (component
  "rate-limit-allow-only-recent"
  (let [limit?-two-today-only (time-utils/queue-rate-limit-allow-only-recent-tasks
                               {:period-s     (* 60 60 24)
                                :limit-recent 2})
        within-limit          (qi (Duration/ofHours 12))
        outside-limit         (qi (Duration/ofHours 36))]
    (assertions
     "Lock when non are within the time limit"
     (limit?-two-today-only [outside-limit] [] [])
     =>
     true
     (limit?-two-today-only [outside-limit] [outside-limit] [outside-limit])
     =>
     true

     "Unlocked when one is within the time limit"
     (limit?-two-today-only [within-limit] [outside-limit] [outside-limit])
     =>
     false
     (limit?-two-today-only [within-limit within-limit within-limit] [outside-limit] [outside-limit])
     =>
     false

     "Locked when some available but too many have been processed"
     (limit?-two-today-only [within-limit] [within-limit] [within-limit])
     =>
     true
     (limit?-two-today-only [within-limit] [within-limit within-limit] [within-limit])
     =>
     true
     (limit?-two-today-only [within-limit] [within-limit within-limit] [])
     =>
     true
     (limit?-two-today-only [within-limit] [] [within-limit within-limit])
     =>
     true
    )))

 (component
  "simple-queue-compose"
  (assertions
   "Or means if there is a lock, it will propagate"
   ((simple-queue/comp-or_lockout_rate-limit
     (constantly true)
     (constantly false))
    nil)
   =>
   true

   ((simple-queue/comp-or_lockout_rate-limit
     (constantly false)
     (constantly false))
    nil)
   =>
   false


   "And means if any is unlocked, then there is no lock."
   ((simple-queue/comp-and_lockout_rate-limit
     (constantly true)
     (constantly false))
    nil)
   =>
   false
   ((simple-queue/comp-and_lockout_rate-limit
     (constantly true)
     (constantly true))
    nil)
   =>
   true))

 (component
  "fetch-metadata-rate-limit"
  (let [limit?-one-daily+four-if-new (le.fetch-metadata/rate-limit-fn {:downloads-per-day 1})
        within-limit                 (qi (Duration/ofHours 12))
        outside-limit                (qi (Duration/ofHours 36))]
    (assertions
     "Unlock for an item after day passed"
     (limit?-one-daily+four-if-new [outside-limit] [] [])
     =>
     false
     (limit?-one-daily+four-if-new [outside-limit] [] [outside-limit outside-limit])
     =>
     false
     (limit?-one-daily+four-if-new [outside-limit] [] [outside-limit outside-limit outside-limit])
     =>
     false

     (limit?-one-daily+four-if-new [outside-limit] [within-limit] [outside-limit outside-limit])
     =>
     true
     (limit?-one-daily+four-if-new [outside-limit] [] [within-limit outside-limit outside-limit])
     =>
     true

     "Unlock for multiple new daily ents"
     (limit?-one-daily+four-if-new [within-limit] [] [within-limit])
     =>
     false
     (limit?-one-daily+four-if-new [within-limit] [] [within-limit within-limit within-limit])
     =>
     false
     (limit?-one-daily+four-if-new [within-limit] [] [within-limit within-limit within-limit outside-limit])
     =>
     false
     (limit?-one-daily+four-if-new [within-limit] [] [within-limit within-limit within-limit within-limit])
     =>
     true

     "Unlock only if zero are active"
     (limit?-one-daily+four-if-new [within-limit] [within-limit] [within-limit])
     =>
     true))))
