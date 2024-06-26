(ns personal-rss-feed.time-utils
  (:require
   [chime.core :as chime]
   [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
   [taoensso.encore :as enc]
   [taoensso.timbre :as log])
  (:import (java.time Duration Instant)))

(defn repeat-every!
  "(fn f [time] any?). 
  
   Can be halted with (.close (repeat-every! ...))"
  [ms-or-duration f]
  (chime/chime-at (rest (chime/periodic-seq
                         (Instant/now)
                         (cond-> ms-or-duration
                           (number? ms-or-duration) (Duration/ofMillis))))
                  f))

(defn queue-rate-limit-x-per-period
  "Rate limit the number of tasks a queue can pick up. Allows `limit-count` number of tasks to be processed successfully
   per `period-s` seconds. Retries are not counted. For example, if the limit is 2 per second, you can immediately dequeue 
   the top 2. If they error immediately, they can be dequeued immediately again, since they stopped counting against the 
   `limit-count`."
  [{:keys [period-s limit-count]}]
  (fn queue-rate-limit-x-per-period* [_waiting-reversed active recent]
    (if (zero? limit-count)
      true ;; true == always locked.
      (boolean
       (enc/when-let [timebox-end (nth (concat active recent) (dec limit-count) nil)
                      time        (::queue-item/activation-time timebox-end)
                      time        (.toInstant time)
                      dur         (Duration/between time (Instant/now))]
         (< (/ (.toMillis dur) 1000) period-s))))))

(defn queue-item-within-period-of-now?
  [period-s queue-item]
  (enc/when-let [time (::queue-item/submission-time queue-item)
                 time (.toInstant time)
                 dur  (Duration/between time (Instant/now))]
    (< (/ (.toMillis dur) 1000) period-s)))

(defn queue-rate-limit-allow-only-recent-tasks
  "Rate limit only allowing tasks submitted today. Only cares about the highest priority item. Most useful
  in comp-and_lockout_rate-limit with some other normal rate limiter, since this will never allow old
  tasks to be processed."
  [{:keys [period-s limit-recent]}]
  (fn queue-rate-limit-x-per-period* [waiting-reversed active recent]
    (let [count-allowed-because-recent
          (count (filter (partial queue-item-within-period-of-now? period-s)
                         (concat active recent)))]
      (if (and (queue-item-within-period-of-now? period-s (first waiting-reversed))
               (not (<= limit-recent count-allowed-because-recent)))
        false ;; not locked when 1. recent and 2. not too many recent have been processed
        true))))

(defn queue-lockout-backoff-retry
  [{:keys [base-s-backoff]}]
  (fn queue-lockout?-backoff-retry*
    [_system {::queue-item/keys [activation-time status retry-count]}]
    (when (and (= status ::queue-item/error-retrying)
               activation-time)
      (let [minimum-delay (* base-s-backoff retry-count)
            run-after     (.plusSeconds (.toInstant activation-time) minimum-delay)
            difference    (Duration/between run-after (Instant/now))]
        (boolean (< (.toSeconds difference) minimum-delay))))))
