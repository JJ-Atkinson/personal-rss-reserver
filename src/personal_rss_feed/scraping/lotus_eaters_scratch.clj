(ns personal-rss-feed.scraping.lotus-eaters-scratch
  (:require
   [clojure.string :as str]
   [personal-rss-feed.playwright.wally-utils :as w-utils]
   [wally.main :as w]
   [garden.selectors :as s]
   [personal-rss-feed.playwright.selectors :as ws])
  )

(def root-address "https://lotuseaters.com")

(defn realize-url 
  [relative-or-absolute]
  (cond (str/starts-with? relative-or-absolute "http")
        relative-or-absolute

        (str/starts-with? relative-or-absolute "//")
        (str "https:" relative-or-absolute)
        
        (str/starts-with? relative-or-absolute "/")
        (str root-address relative-or-absolute)
        
        :else 
        (str (w/url) \/ relative-or-absolute)))


(defn parse-episode-name
  [ep-name]
  ;; https://regex101.com/r/UQ9u0Z/2
  (let [[_ series? ep-number? title]
        (re-matches #"PREMIUM: (?:([\w ]+)(?:#(\d+))?.?\|)? ?(.*)" ep-name)]
    (when title
      (cond-> {:episode/title (str/trim title)}
        series? (assoc :episode/series (str/trim series?))
        ep-number? (assoc :episode/ep-number ep-number?)))))

(defn get-title-meta
  [description-block]
  (-> (.locator description-block (w/query->selector [".pageListingItem__title"]))
    (w/text-content)
    (parse-episode-name)))

(defn get-series-meta 
  [description-block]
  {:episode/series
   (-> (.locator description-block (w/query->selector [".pageListingItem__category"]))
     (w/text-content)
     (str/trim))})

(defn get-excerpt-meta
  [description-block]
  {:episode/excerpt
   (-> (.locator description-block (w/query->selector [".pageListingItem__excerpt"]))
     (w/text-content)
     (str/trim))})

(defn get-site-url
  [description-block]
  {:episode/url
    (-> (.locator description-block (w/query->selector [".pageListingItem__excerpt" s/a]))
     (.getAttribute "href")
     (realize-url))})

(defn get-thumbnail
  [description-block]
  {:episode/thumbnail-origin-uri 
   (-> (.locator description-block (w/query->selector [".pageListingItem__image" s/img]))
     (.getAttribute "src")
     (realize-url))})

(defn scan-episodes-premium-page
  "Given the `https://lotuseaters.com/premium-dashboard`, scan the contents for podcast episodes. They should contain the following keys:
  
  {:episode/url ;; Used in the filter-fn
   :episode/thumbnail-origin-uri
   :episode/excerpt
   :episode/series
   :episode/title
   :episode/ep-number ;; Optional
   }
  "
  [filter-fn]
  (for [block (ws/query [".pageListing__content" ".pageListingItem"])
        :let [ep-title-meta (get-title-meta block)
              site-url-meta (get-site-url block)]
        :when (and ep-title-meta
                (filter-fn site-url-meta))]
    (merge-with #(or %1 %2)
      (get-series-meta block)
      ep-title-meta
      (get-excerpt-meta block)
      site-url-meta
      (get-thumbnail block))))

(defn scroll-and-wait
  []
  (.press (.keyboard w/*page*) "End")
  (when (ws/query-now (ws/text "Load More"))
    (.click (ws/query-1 (ws/text "Load More"))))
  (w/wait-for [".pageListing--standard" ".loading"]
    {:state :attached
     :timeout 10000})
  (w/wait-for [".pageListing--standard" ".loading"]
    {:state :detached
     :timeout 10000}))

;; Required. Move this later.
(System/setProperty "playwright.cli.dir" (System/getenv "PLAYWRIGHT_CLI_LOCATION"))

(comment
  (scan-episodes-premium-page (constantly true))
  (scroll-and-wait)
  (w-utils/with-page (w-utils/fresh-page {:debug {}})
    (w-utils/navigate "https://lotuseaters.com")
    (.setViewportSize w/*page* 1920 1080)

    (.click (ws/query-1 [".fa-sign-in-alt"]))

    (w/fill (ws/query [".modal" ".input--email" s/input])
      "REPLACEME")
    (w/fill (ws/query [".modal" ".input--password" s/input])
      "REPLACEME")
    (w/click (ws/query [".modal" (ws/text "Submit")]))
    (w/navigate "https://lotuseaters.com/premium-dashboard")

    #_#_#_(.highlight (first (ws/query [".modal" s/button])))

            (first (ws/query [".pageListing__items" s/div]))

            (w/text-content (.locator (first (ws/query [".pageListing__items" s/div]))
                              (w/query->selector [".pageListingItem__title"])))

    (break!)

    )

  (ws/query [".pageListing__items .pageListingItem"])
  (def item (first (ws/query [".pageListing__items .pageListingItem"])))
  (.getAttribute (.locator item (w/query->selector [".pageListingItem__title" s/a]))
    "href")


  (doseq [a [1 2 3]]
    (println a)
    (com.gfredericks.debug-repl/break!))
  (break!)
  )

