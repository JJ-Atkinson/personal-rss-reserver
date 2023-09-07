(ns personal-rss-feed.ingest.lotus-eaters
  (:require
   [clojure.string :as str]
   [personal-rss-feed.playwright.wally-utils :as w-utils]
   [wally.main :as w]
   [garden.selectors :as s]
   [personal-rss-feed.playwright.selectors :as ws]
   [clj-http.client :as http]
   [clojure.data.xml :as xml]))

(def root-address "https://lotuseaters.com")

(defn episode-page-get-content-url
  "In the context of an existing navigated page, get the CDN url for the download"
  []
  {:episode/audio-original-uri
   (.getAttribute (ws/query-1 [".post__body" (ws/title "Download Audio File")])
     "href")})

(defn get-detailed-information
  [ep-url]
  (w-utils/navigate ep-url)
  (episode-page-get-content-url))

(defn login
  []
  (w-utils/navigate root-address)
  (.setViewportSize w/*page* 1200 800)

  (.click (ws/query-1 [".fa-sign-in-alt"]))

  (w/fill (ws/query [".modal" ".input--email" s/input])
    (System/getenv "LE_USERNAME"))
  (w/fill (ws/query [".modal" ".input--password" s/input])
    (System/getenv "LE_PASSWORD"))
  (w/click (ws/query [".modal" (ws/text "Submit")]))
  
  (w/navigate "https://lotuseaters.com/premium-dashboard"))

(defn augment-episode-information
  [episodes]
  (w-utils/with-page (w-utils/fresh-page {:debug {}})
    (.setDefaultTimeout w/*page* 20000)
    (login)

    (break!)
    (->> episodes
      (map (fn [{:episode/keys [url] :as ep}]
             (merge ep
               (get-detailed-information url))))
      (doall))
    ))

(comment
  ;; Required for nix. Move this later.
  (System/setProperty "playwright.cli.dir" (System/getenv "PLAYWRIGHT_CLI_LOCATION"))

  (augment-episode-information [])


  (ws/query [".pageListing__items .pageListingItem"])
  (def item (first (ws/query [".pageListing__items .pageListingItem"])))
  (.getAttribute (.locator item (w/query->selector [".pageListingItem__title" s/a]))
    "href")
  (w/navigate "https://lotuseaters.com/premium-why-ideology-is-theology-14-07-23")
  (.getAttribute (ws/query-1 [".post__body" (ws/title "Download Audio File")])
    "href")

  (str/trim (w/text-content (ws/query-1 [".post__body" s/div s/p s/b s/i])))
  (get-detailed-information "https://www.lotuseaters.com/premium-brokenomics-4-or-debt-and-deficits-no-good-options-from-here-17-01-23" )

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

(def rss-str->episodes
  (comp
    (fn [intermediate]
      {:episodes (->> (:items intermediate)
                   (map (partial merge (select-keys intermediate [:podcast/id]))))
       :podcast (dissoc intermediate :items)})
    (map-accumulator
      [(attribute :title :podcast/title content)
       (attribute :icon :podcast/icon content)
       (attribute :link :podcast/feed-uri (comp :href :attrs))
       (attribute :updated :podcast/updated-at content)
       (attribute :description :podcast/description content)
       (attribute :id :podcast/id content)
       (attributes :item :items
         (navigate
           :content
           (map-accumulator
             [(attribute :title :episode/title #(str/trim (second (str/split (content %) #"\|"))))
              (attribute :description :episode/thumbnail-origin-uri #(nth (parse-description (content %)) 1))
              (attribute :description :episode/url #(nth (parse-description (content %)) 0))
              (attribute :description :episode/excerpt #(nth (parse-description (content %)) 2))
              (attribute :pubDate :episode/publish-date content)])))])
    #(-> (xml/parse-str %) :content first :content)))

(comment
  (def resp
    (http/get
      "https://lotuseaters.com/feed/category/brokenomics"))
  (xml/parse-str (:body resp))

  (rss-str->episodes (:body resp))
  )

