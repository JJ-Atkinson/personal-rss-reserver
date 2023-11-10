(ns personal-rss-feed.ingest.lotus-eaters
  (:require
   [clojure.string :as str]
   [dev.freeformsoftware.simple-queue.core :as simple-queue]
   [dev.freeformsoftware.simple-queue.queue :as queue]
   [integrant.core :as ig]
   [personal-rss-feed.playwright.wally-utils :as w-utils]
   [wally.main :as w]
   [garden.selectors :as s]
   [personal-rss-feed.playwright.selectors :as ws]
   [clj-http.client :as http]
   [clojure.data.xml :as xml]
   [babashka.http-client :as bb.http]
   [clojure.java.io :as io :refer [output-stream]]
   [remus])
  (:import
   (java.time LocalDateTime ZoneId)
   (java.time.format DateTimeFormatter)
   (java.util Date)))

(def root-address "https://lotuseaters.com")

(defn login!
  [env]
  (.click (ws/query-1 [".fa-sign-in-alt"]))

  (w/fill (ws/query [".modal" ".input--email" s/input])
    (System/getenv "LE_USERNAME"))
  (w/fill (ws/query [".modal" ".input--password" s/input])
    (System/getenv "LE_PASSWORD"))
  (w/click (ws/query [".modal" (ws/text "Submit")])))

(defn ensure-logged-in!
  []
  (when-not (ws/query-1-now [".fa-sign-out-alt"])
    (login! nil)))

(defn safe-navigate!
  "For some reason playwright doesn't recognize the lotus eaters site as loaded ever. Awaits the appearance of the nav bar."
  [url]
  (.setDefaultTimeout w/*page* 5000)
  (try
    (w-utils/navigate url)
    (catch Exception e nil))
  (.setDefaultTimeout w/*page* 20000)
  (ws/query-1 [".navBar__left"]))

(defn episode-page-get-content-url
  "In the context of an existing navigated page, get the CDN url for the download"
  []
  (ensure-logged-in!)
  {:episode/audio-origin-uri
   (.getAttribute (ws/query-1 [".post__body" (ws/title "Download Audio File")])
     "href")})

(defn get-detailed-information
  [ep-url]
  (safe-navigate! ep-url)
  (episode-page-get-content-url))



(defn augment-episode-information
  [episodes]
  (w-utils/with-page (w-utils/fresh-page {:debug {}})
    (.setDefaultTimeout w/*page* 20000)
    ;;(break!)

    ;;#_
    (->> episodes
      (map (fn [{:episode/keys [url] :as ep}]
             (merge ep
               (get-detailed-information url))))
      (doall))
    ))

(comment

  (augment-episode-information
    [{:podcast/id                   "https://www.lotuseaters.com/feed/category_brokenomics",
      :episode/title                "Big Ideas",
      :episode/thumbnail-origin-uri "https://images.ctfassets.net/khed0tvttjco/62r9kdzk9IbrqLRPBMcA0Y/5181641a530e5e1c1e7f30a93cc1250c/17.5_Big_-_Prem.jpg",
      :episode/url                  "https://www.lotuseaters.com/premium-brokenomics-17-or-five-big-innovation-platforms-18-04-23",
      :episode/excerpt              "The world is about to experience a combination of new technologies that could radically alter our economic trajectory and change the way we live and work forever - Dan looks at these technologies and what they might mean",
      :episode/publish-date         "2023-04-18T15:00:00+00:00"}])


  (ws/query [".pageListing__items .pageListingItem"])
  (def item (first (ws/query [".pageListing__items .pageListingItem"])))
  (.getAttribute (.locator item (w/query->selector [".pageListingItem__title" s/a]))
    "href")
  (w/navigate "https://lotuseaters.com/premium-why-ideology-is-theology-14-07-23")
  (.getAttribute (ws/query-1 [".post__body" (ws/title "Download Audio File")])
    "href")

  (str/trim (w/text-content (ws/query-1 [".post__body" s/div s/p s/b s/i])))
  (get-detailed-information "https://www.lotuseaters.com/premium-brokenomics-4-or-debt-and-deficits-no-good-options-from-here-17-01-23")

  )

(defn parse-description
  [desc-str]
  (drop 1
    (re-matches #".*\<a href=\"(.*)\" ?>.*\<img src=\"(.*)\" ?\/>.*\<br\>(.*).*<br>"
      (-> desc-str
        (str/trim)
        (str/replace "\t" "")
        (str/replace "\n" "")))))

(defn content
  [xml-tag]
  (first (:content xml-tag)))

(defn attribute
  ([key as value-extraction]
   (fn [xml--attrs-list]
     (as-> (filter #(= key (:tag %)) xml--attrs-list) $
       (first $)
       (value-extraction $)
       {as $})))
  ([key value-extraction]
   (attribute key key value-extraction)))

(defn navigate
  [path extractor]
  (fn [xml]
    (extractor (path xml))))

(defn map-accumulator
  [extractors]
  (fn [xml]
    (into {}
      (map (fn [ex] (ex xml)))
      extractors)))

(defn attributes
  ([key as value-extraction]
   (fn [xml--attrs-list]
     (as-> (filter #(= key (:tag %)) xml--attrs-list) $
       (map value-extraction $)
       {as $})))
  ([key value-extraction]
   (attributes key key value-extraction)))


(defn parse-date
  [s]
  (when-let [ldt (some-> s (LocalDateTime/parse DateTimeFormatter/ISO_OFFSET_DATE_TIME))]
    (Date/from (.toInstant (.atZone ldt (ZoneId/of "UTC"))))))


(def rss-str->episodes
  (comp
    (fn [intermediate]
      {:episodes (->> (:items intermediate)
                   (map (partial merge (select-keys intermediate [:podcast/id]))))
       :podcast  (dissoc intermediate :items)})
    (map-accumulator
      [(attribute :title :podcast/title content)
       (attribute :icon :podcast/icon-uri content)
       (attribute :updated :podcast/updated-at (comp parse-date content))
       (attribute :description :podcast/description content)
       (attributes :item :items
         (navigate
           :content
           (map-accumulator
             [(attribute :title :episode/title #(str/trim (second (str/split (content %) #"\|"))))
              (attribute :description :episode/thumbnail-origin-uri #(nth (parse-description (content %)) 1))
              (attribute :description :episode/url #(nth (parse-description (content %)) 0))
              (attribute :description :episode/excerpt #(nth (parse-description (content %)) 2))
              (attribute :pubDate :episode/publish-date (comp parse-date content))])))])
    #(-> (xml/parse-str %) :content first :content)))

(comment
  (def resp
    (http/get
      "https://lotuseaters.com/feed/category/brokenomics"))
  (def resp2 (remus/parse-url "https://lotuseaters.com/feed/category/brokenomics"))
  (xml/parse-str (:body resp))

  (rss-str->episodes (:body resp))

  (http/head "https://cdn.lotuseaters.com/23.03.28-Brokenomics17-5_Big_Innovation_Platforms(P).mp3")
  )




(comment
  (def s3 (aws/client {:api               :s3
                       :endpoint-override {:protocol :http
                                           :hostname "garage-ct.lan"
                                           :port     3900}
                       :region            "us-west-1"
                       }))
  (aws/doc s3 :PutObject)
  (aws/invoke s3 {:op :ListBuckets})

  (let [request (bb.http/get "https://rss-feeds.us-southeast-1.linodeobjects.com/dads.mp3" {:as :stream})]
    (with-open [body (:body request)]
      (println (type body))
      (aws/invoke s3 {:op      :PutObject
                      :request {:Bucket        "lotus-eaters"
                                :Key           "dads.mp3"
                                :ContentType   (get-in request [:headers "content-type"])
                                :ContentLength (Integer. (get-in request [:headers "content-length"] 0))
                                :Body          body}})))

  (with-open [body (clojure.java.io/input-stream "dads-linode.mp3")]
    (println (type body))
    (aws/invoke s3 {:op      :PutObject
                    :request {:Bucket "lotus-eaters"
                              :Key    "dads.mp3"
                              :Body   body}}))

  (java.util.Date. "2023-10-17T15:00:00+00:00"))


(defmethod ig/init-key ::lotus-eaters-ingest
  [_ {:keys [start-cron? apply-playwright-cli-fix? queue] :as options}]
  (when apply-playwright-cli-fix?
    ;; Required for nix.
    (System/setProperty "playwright.cli.dir" (System/getenv "PLAYWRIGHT_CLI_LOCATION")))

  (simple-queue/qadd! queue {::queue/name                :lotus-eaters/fetch-metadata
                             ::queue/default-retry-limit 2})
  (simple-queue/qadd! queue {::queue/name :lotus-eaters/download-audio})
  (simple-queue/qadd! queue {::queue/name :lotus-eaters/download-video})
  (simple-queue/qadd! queue {::queue/name :lotus-eaters/transcribe-audio})
  (simple-queue/qadd! queue {::queue/name :lotus-eaters/upload-s3})

  )

(defmethod ig/suspend-key! ::lotus-eaters-ingest
  [_ _])

(defmethod ig/resume-key ::lotus-eaters-ingest
  [_ _ _ _])

(defmethod ig/halt-key! ::lotus-eaters-ingest
  [_ conn]
  )