(ns personal-rss-feed.ingest.lotus-eaters.fetch-metadata
  (:require
   [clojure.string :as str]
   [dev.freeformsoftware.simple-queue.core :as simple-queue]
   [personal-rss-feed.ingest.lotus-eaters.download-file :as le.download-file]
   [dev.freeformsoftware.simple-queue.queue :as queue]
   [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
   [personal-rss-feed.playwright.wally-utils :as w-utils]
   [personal-rss-feed.time-utils :as time-utils]
   [taoensso.encore :as enc]
   [taoensso.timbre :as log]
   [wally.main :as w]
   [garden.selectors :as s]
   [personal-rss-feed.playwright.selectors :as ws]
   [personal-rss-feed.ingest.lotus-eaters.shared :as le.shared]
   [personal-rss-feed.feed.db :as db]
   [datalevin.core :as d]
   [remus])
  (:import (java.time ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Calendar Date Locale)))


(defmacro e->nil [form] `(try ~form (catch Exception e# nil)))

(defn login!
  [{:lotus-eaters/keys [username password] :as shared}]
  (.click (ws/query-1 [".fa-sign-in-alt"]))

  (w/fill (ws/query [".modal" ".input--email" s/input]) username)
  (w/fill (ws/query [".modal" ".input--password" s/input]) password)
  (w/click (ws/query [".modal" (ws/text "Submit")])))

(defn ensure-logged-in!
  [shared]
  (when-not (ws/query-1-now [".fa-sign-out-alt"])
    (login! shared)))

(defn safe-navigate!
  "For some reason playwright doesn't recognize the lotus eaters site as loaded ever. Awaits the appearance of the nav bar."
  [url]
  (.setDefaultTimeout w/*page* 10000)
  (try
    (w-utils/navigate url)
    (catch Exception e nil))
  (.setDefaultTimeout w/*page* 20000)
  (ws/query-1 [".navBar__left"])
  (.setDefaultTimeout w/*page* 10000))

(defn parse-date
  [date-s]
  (let [conforms? (re-matches #"\d+\w+ \w{3}, \d{4}.+" date-s) ;; does it specify year?
        date-s    (if-not conforms? (str date-s " " (.get (Calendar/getInstance) (Calendar/YEAR))) date-s)
        date-s    (-> date-s
                    (str/replace "pm" "PM")
                    (str/replace "am" "AM"))
        formatter (DateTimeFormatter/ofPattern "d['th']['st']['nd'] MMM[', 'yyyy] 'at' hh:mm a[' 'yyyy]" Locale/ENGLISH)
        zoned-dt  (ZonedDateTime/parse date-s (.withZone formatter (ZoneId/of "GMT")))]
    (Date/from (.toInstant zoned-dt))))

(comment
  (parse-date "8th Nov at 03:00 am")
  (parse-date "8th Nov, 2021 at 03:00 pm"))

(defn blob->nil
  [s]
  (when-not (str/starts-with? s "blob:")
    s))

(defn episode-page-get-content-url
  "In the context of an existing navigated page, get the CDN url for the download"
  [!shared]
  (ensure-logged-in! !shared)
  (Thread/sleep 2000)
  (enc/assoc-some {}
    :episode/audio-original-uri
    (e->nil (.getAttribute (ws/query-1 [".post__body" (ws/title "Download Audio File")]) "href"))

    :episode/video-original-uri
    (or
      (e->nil (.getAttribute (ws/query-1 [".post__body" (ws/title "Download Video File (720P)")]) "href")) ;; Initial download link
      (e->nil (.getAttribute (ws/query-1 [".post__body" (ws/title "Download Video File")]) "src")) ;; Fallback when only one res exists

      ;; Hangouts/interview videos are rumble only for now. Query the video element
      (e->nil (blob->nil (.getAttribute (first (ws/query [".post__body" ".embeddedEntry" "video"])) "src")))
      (e->nil (blob->nil (.getAttribute (second (ws/query [".post__body" ".embeddedEntry" "video"])) "src"))))

    :episode/excerpt
    (e->nil (.textContent (first (ws/query [".post__body" s/div s/p s/span]))))

    :episode/publish-date
    (e->nil (parse-date (.textContent (first (ws/query [".post__metaDetails" s/p s/b])))))

    :episode/title                                          ;; trims "Premium: Brokenomics | Argentina" 
    (e->nil (let [text (.textContent (first (ws/query [".post__intro" s/h1])))]
              (if (str/starts-with? text "PREMIUM")
                (-> text
                  (str/split #"\|")
                  (second)
                  (str/trim))
                text)))))

(defn get-detailed-information
  [{::keys [browser-context debug] :as shared} {:keys [episode/url]}]
  (w-utils/with-page (merge (w-utils/fresh-page
                              {:browser-context browser-context
                               :debug           debug})
                       {:autoclose-browser-context? false})
    (safe-navigate! url)
    (let [extra-meta (episode-page-get-content-url shared)]
      (when (and (not (contains? extra-meta :episode/audio-original-uri))
              (not (contains? extra-meta :episode/video-original-uri)))
        (throw (ex-info "Was not able to get any audio/video information from the page" {})))
      (assoc extra-meta :episode/url url))))

(defn augment-episode-information
  [{::le.shared/keys [queue]
    :db/keys         [conn] :as shared}
   {::queue-item/keys              [id]
    {:keys [:episode/url] :as ctx} ::queue-item/data
    :as                            queue-item}]
  (log/info "augment-episode-information for " queue-item)
  (try
    (let [episode (get-detailed-information shared ctx)]
      (db/save-episode! conn episode)
      (simple-queue/qsubmit! queue
        #::queue-item{:queue    ::le.download-file/download-queue
                      :id       (random-uuid)
                      :data     (assoc (select-keys episode [:episode/url])
                                  ::le.download-file/download-type
                                  (if (contains? episode :episode/audio-original-uri)
                                    ::le.download-file/audio
                                    ::le.download-file/video))
                      :priority (or (some-> episode :episode/publish-date (.getTime)) 0)})
      (simple-queue/qcomplete! queue id)
      episode)
    (catch Exception e
      (simple-queue/qerror! queue id {:exception                (pr-str e)
                                      ::simple-queue/retryable? true}))))

(defn init!
  [shared]
  (let [browser-context (w-utils/fresh-browser-context {})]
    (-> shared
      (assoc ::browser-context browser-context)
      (update ::le.shared/close-on-halt conj browser-context)
      (le.shared/start-queue!
        {:queue-conf {::queue/name                ::fetch-metadata-queue
                      ::queue/default-retry-limit 3
                      ::queue/rate-limit-fn       (simple-queue/comp-and_lockout_rate-limit
                                                    (time-utils/queue-rate-limit-x-per-period
                                                      {:period-s    (* 60 60 24)
                                                       :limit-count (:downloads-per-day shared)})
                                                    (time-utils/queue-rate-limit-allow-only-recent-tasks
                                                      {:period-s (* 60 60 24)}))
                      ::queue/timeout?-fn         (simple-queue/default-timeout?-fn (* 1000 160))
                      ::queue/lockout?-fn         (time-utils/queue-lockout-backoff-retry
                                                    {:base-s-backoff (* 60 60 3)})} ;; runs at 0h, 3h, (3+6h) 9h, (6+9h) 15h
         :poll-ms    1000
         :poll-f     #'augment-episode-information}))))

(comment

  (defn with-debug [shared] (assoc shared ::debug {}))

  (get-detailed-information (with-debug @le.shared/!shared)
    {:episode/url "..."})

  
  (peek (simple-queue/qview
          (::le.shared/queue @le.shared/!shared)
          ::fetch-metadata-queue))
  
  (doseq [qi (simple-queue/qview
               (::le.shared/queue @le.shared/!shared)
               ::fetch-metadata-queue)]
    (let [ep-data (db/episode-by-url (d/db (:db/conn @le.shared/!shared)) (:episode/url (::queue-item/data qi)))]
      (simple-queue/update!qi
        (::le.shared/queue @le.shared/!shared)
        (::queue-item/id qi)
        ::queue-item/priority (constantly (or (e->nil (.getTime (:episode/publish-date ep-data)))
                                            0)))))

  (simple-queue/-qsort!
    (::le.shared/queue @le.shared/!shared)
    ::fetch-metadata-queue)

  (do
    (simple-queue/update!q
      (::le.shared/queue @le.shared/!shared)
      ::fetch-metadata-queue
      ::queue/rate-limit-fn (constantly (time-utils/queue-rate-limit-x-per-period
                                          {:period-s    (* 60 60 24)
                                           :limit-count 8})))
    nil)

  ((simple-queue/comp-and_lockout_rate-limit
     (time-utils/queue-rate-limit-x-per-period
       {:period-s    (* 60 60 24)
        :limit-count 8})
     (time-utils/queue-rate-limit-allow-only-recent-tasks
       {:period-s (* 60 60 24)}))
   [{::queue-item/submission-time #inst"2023-11-20T22:09:53.828-00:00"}]
   (repeat 8 {::queue-item/activation-time (Date.)}) nil)

  (filter
    (comp (partial = "https://www.lotuseaters.com/premium-the-politics-of-skyrim-29-11-23") :episode/url ::queue-item/data)
    (map-indexed (fn [i q]
                   (assoc q :i i))
      (simple-queue/qview
        (::le.shared/queue @le.shared/!shared)
        ::fetch-metadata-queue)))

  (augment-episode-information
    @le.shared/!shared
    (simple-queue/manual-dequeue!
      (::le.shared/queue @le.shared/!shared)
      #uuid"d47f3a16-a501-4fd5-b45d-b024422d1b23"))


  (count
    (simple-queue/qview-dead
      (::le.shared/queue @le.shared/!shared)
      ::fetch-metadata-queue))


  (simple-queue/all-un-resolved-errors
    (::le.shared/queue @le.shared/!shared)
    ::fetch-metadata-queue)

  (simple-queue/qresubmit-item! (::le.shared/queue @le.shared/!shared) #uuid "cafd67d2-7735-4671-b7b6-eddcbe03dc5c")

  (swap! simple-queue/*manual-unlock-1*
    conj ::fetch-metadata-queue)
  (simple-queue/qview-active (::le.shared/queue @le.shared/!shared) ::fetch-metadata-queue)
  (simple-queue/resolve!i (::le.shared/queue @le.shared/!shared) #uuid "cafd67d2-7735-4671-b7b6-eddcbe03dc5c")

  (do
    (swap! simple-queue/*manual-unlock-1*
      conj ::fetch-metadata-queue)
    (augment-episode-information
      (with-debug @le.shared/!shared)
      (simple-queue/qpop!
        (::le.shared/queue @le.shared/!shared)
        ::fetch-metadata-queue))))
