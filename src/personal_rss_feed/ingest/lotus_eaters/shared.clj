(ns personal-rss-feed.ingest.lotus-eaters.shared
  (:require
   [clojure.tools.namespace.repl :as tools.namespace]
   [dev.freeformsoftware.simple-queue.core :as simple-queue]
   [dev.freeformsoftware.simple-queue.queue :as queue]
   [personal-rss-feed.time-utils :as time-utils]
   [taoensso.timbre :as log]))

(tools.namespace/disable-reload!)

(def !shared (atom nil))                                   ;; useful for debugging, atom containing !shared

(defn start-queue!
  "(fn poll-f [shared queue-item] ...)"
  [shared {:keys [queue-conf
                  poll-ms
                  poll-f]}]
  (simple-queue/qcreate! (::queue shared) queue-conf)
  (cond-> shared

    (:start-auto-poll? shared true)
    (update ::close-on-halt
      conj
      (time-utils/repeat-every!
        poll-ms
        (fn [_]
          (try
            (some->>
              (simple-queue/qpop! (::queue shared) (::queue/name queue-conf))
              (poll-f shared))
            (catch Exception e
              (log/error "Hit error processing queue item!" e)
              (throw e))))))))

(defn halt!
  [shared]
  (doseq [closable (::close-on-halt shared)]
    (.close closable))
  (log/info "Closed lotus-eaters-closable"))
