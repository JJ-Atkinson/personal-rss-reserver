(ns personal-rss-feed.ingest.lotus-eaters.download-file
  (:require [datalevin.core :as d]
            [dev.freeformsoftware.simple-queue.core :as simple-queue]
            [dev.freeformsoftware.simple-queue.queue :as queue]
            [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
            [personal-rss-feed.feed.s3 :as s3]
            [personal-rss-feed.feed.db :as db]
            [personal-rss-feed.name-utils :as name-utils]
            [personal-rss-feed.ingest.lotus-eaters.shared :as le.shared]
            [personal-rss-feed.ingest.lotus-eaters.extract-audio :as le.extract-audio]
            [personal-rss-feed.time-utils :as time-utils]
            [taoensso.timbre :as log]))

(defn download-episode
  [{:db/keys         [conn]
    ::le.shared/keys [queue s3]}
   {::queue-item/keys                      [id]
    {:keys [:episode/url ::download-type]} ::queue-item/data
    :as                                    queue-item}]
  (log/info "Downloading file" url download-type)
  (try
    (let [episode        (db/episode-by-url (d/db conn) url)
          ep-uuid        (:episode/uuid episode)
          download-uri   (case download-type
                           ::audio (:episode/audio-original-uri episode)
                           ::video (:episode/video-original-uri episode))
          dest-key       (case download-type
                           ::audio (name-utils/format-audio ep-uuid)
                           ::video (name-utils/format-video ep-uuid (:episode/video-original-uri episode)))
          content-length (s3/upload-uri! s3 download-uri dest-key)]
      (cond (= ::audio download-type)
            (db/save-episode! conn {:episode/url                  url
                                    :episode/audio-content-length content-length})
            (= ::video download-type)
            (db/save-episode! conn {:episode/url                  url
                                    :episode/video-content-length content-length})
            :else nil)
      (log/info "Successfully downloaded the file!" url dest-key)
      (simple-queue/qcomplete! queue id {:destination    dest-key
                                         :content-length content-length
                                         :download-url   download-uri})
      (when (= ::video download-type)
        (simple-queue/qsubmit! queue {::queue-item/queue    ::le.extract-audio/extract-audio-queue
                                      ::queue-item/id       (random-uuid)
                                      ::queue-item/data     (select-keys episode [:episode/url])
                                      ::queue-item/priority (.getTime (:episode/publish-date episode))})))
    (catch Exception e
      (simple-queue/qerror! queue id {:exception                (pr-str e)
                                      ::simple-queue/retryable? true}))))

(defn download-now!
  "Trigger an immediate download. Returns a queue item representing the task. Does _not_ re-submit a failed task."
  [{:db/keys         [conn]
    ::le.shared/keys [queue s3] :as system}
   {:keys [:episode/url ::download-type] :as data}]
  (assert (contains? #{::video ::audio} download-type))
  (let [existing-items       (concat
                               (simple-queue/qview-active queue ::download-queue)
                               (simple-queue/qview queue ::download-queue))
        existing-download-id (->> existing-items
                               (filter #(and (= (get-in % [::queue-item/data :episode/url]) url)
                                          (= (get-in % [::queue-item/data ::download-type]) download-type)))
                               (first)
                               ::queue-item/id)
        new-download         (when-not existing-download-id
                               {::queue-item/queue    ::download-queue
                                ::queue-item/priority Long/MAX_VALUE
                                ::queue-item/id       (random-uuid)
                                ::queue-item/data     data})
        _                    (some->> new-download (simple-queue/qsubmit! queue))
        download-id          (or existing-download-id (::queue-item/id new-download))]
    (when (= ::queue-item/waiting (::queue-item/status (simple-queue/resolve!i queue download-id)))
      (future (download-episode system (simple-queue/manual-dequeue! queue download-id))))
    (simple-queue/resolve!i queue download-id)))

(defn init!
  [shared]
  (le.shared/start-queue! shared
    {:queue-conf {::queue/name                ::download-queue
                  ::queue/default-retry-limit 3
                  ::queue/rate-limit-fn       (time-utils/queue-rate-limit-x-per-period
                                                {:period-s    (* 60 60 24)
                                                 :limit-count (+ 8 (:downloads-per-day shared))})
                  ::queue/timeout?-fn         (simple-queue/default-timeout?-fn (* 1000 200))
                  ::queue/lockout?-fn         (time-utils/queue-lockout-backoff-retry
                                                {:base-s-backoff (* 60 60 3)})}
     :poll-ms    1000
     :poll-f     #'download-episode}))

(comment
  (simple-queue/qpeek!
    (::le.shared/queue @le.shared/!shared)
    ::download-queue)

  (doseq [{::queue-item/keys [id data]} (simple-queue/qview
                                          (::le.shared/queue @le.shared/!shared)
                                          ::download-queue)]
    (let [data (db/episode-by-url (d/db (:db/conn @le.shared/!shared)) (:episode/url data))]
      (simple-queue/update!qi
        (::le.shared/queue @le.shared/!shared)
        id
        ::queue-item/priority (constantly (.getTime (:episode/publish-date data))))
      #_(println id (:episode/publish-date data))))
  (simple-queue/-qsort!
    (::le.shared/queue @le.shared/!shared)
    ::download-queue)


  (d/touch (db/episode-by-url (d/db (:db/conn @le.shared/!shared)) "https://www.lotuseaters.com/premium-why-ideology-is-theology-14-07-23"))

  (db/save-episode! (:db/conn @le.shared/!shared) {:episode/url                "https://www.lotuseaters.com/premium-live-lads-hour-12-or-zombie-apocalypse-21-11-2023"
                                                   :episode/video-original-uri "https://ak2.rmbl.ws/s8/2/3/L/5/j/3L5jo.Faa.rec.mp4"})

  (take 10 (reverse (simple-queue/qview
                      (::le.shared/queue @le.shared/!shared)
                      ::download-queue)))

  (simple-queue/update!qi
    (::le.shared/queue @le.shared/!shared)
    #uuid"33b9d25c-1d28-4045-90b3-72b507096fb3"
    ::simple-queue/lockout-override? (constantly true))
  simple-queue/qpeek!

  (simple-queue/resolve-error! (::le.shared/queue @le.shared/!shared) #uuid"03d76bc6-b9f7-4e8f-9ffe-0956634637cb")

  (simple-queue/all-un-resolved-errors
    (::le.shared/queue @le.shared/!shared)
    ::download-queue)

  (simple-queue/qresubmit-item!
    (::le.shared/queue @le.shared/!shared)
    #uuid"19b55afb-f7b7-48ac-b9f4-25e60cf744d4")

  (simple-queue/qsubmit! (::le.shared/queue @le.shared/!shared)
    (assoc (#'simple-queue/resolve!i (::le.shared/queue @le.shared/!shared) #uuid "50b3a4d9-176e-44bc-9d19-f34798d4dbc2")
      ::queue-item/priority Long/MAX_VALUE
      ::queue-item/retry-count 0))

  (swap! simple-queue/*manual-unlock-1*
    conj ::download-queue)

  (d/touch
    (db/episode-by-url
      (d/db (:db/conn @le.shared/!shared))
      "https://www.lotuseaters.com/premium-the-politics-of-skyrim-29-11-23"))

  (simple-queue/qresubmit-item!
    (::le.shared/queue @le.shared/!shared)
    #uuid"247210c6-d4a2-4e1e-9ba2-9d5911fd31b6")

  (simple-queue/resolve!i
    (::le.shared/queue @le.shared/!shared)
    #uuid"247210c6-d4a2-4e1e-9ba2-9d5911fd31b6")

  (do
    (download-episode
      @le.shared/!shared
      (#'simple-queue/resolve!i (::le.shared/queue @le.shared/!shared) #uuid "50b3a4d9-176e-44bc-9d19-f34798d4dbc2"))
    nil)


  (download-episode @le.shared/!shared
    (simple-queue/qpop!
      (::le.shared/queue @le.shared/!shared)
      ::download-queue))

  )