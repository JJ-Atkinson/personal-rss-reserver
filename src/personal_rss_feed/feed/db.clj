(ns personal-rss-feed.feed.db
  (:require
   [datalevin.core :as d]
   [integrant.core :as ig]))

(def schema {:aka                          {:db/cardinality :db.cardinality/many}
             ;; :db/valueType is optional, if unspecified, the attribute will be
             ;; treated as EDN blobs, and may not be optimal for range queries
             :name                         {:db/valueType :db.type/string
                                            :db/unique    :db.unique/identity}

             :episode/url                  {:db/valueType :db.type/string
                                            :db/unique    :db.unique/identity}
             :episode/title                {:db/valueType :db.type/string}
             :episode/ep-number            {:db/valueType :db.type/string} ;; OPTIONAL!!
             :episode/thumbnail-origin-uri {:db/valueType :db.type/string}
             :episode/excerpt              {:db/valueType :db.type/string}
             :episode/publish-date         {:db/valueType :db.type/instant}
             :episode/audio-original-uri   {:db/valueType :db.type/string}
             :episode/podcast              {:db/valueType :db.type/string}
             :episode/saved-thumbnail-uuid {:db/valueType :db.type/uuid}
             :episode/saved-audio-uuid     {:db/valueType :db.type/uuid}
             :episode/audio-content-length {:db/valueType :db.type/string}
             :episode/saved-video-uuid     {:db/valueType :db.type/uuid}

             :podcast/feed-uri             {:db/valueType :db.type/string
                                            :db/unique    :db.unique/identity} ;; Must be seeded by a user
             :podcast/id                   {:db/valueType :db.type/string} ;; e.g. "brokenomics"
             :podcast/title                {:db/valueType :db.type/string}
             :podcast/icon-uri             {:db/valueType :db.type/string}
             :podcast/description          {:db/valueType :db.type/string}


             :download/id                  {:db/valueType :db.type/uuid
                                            :db/unique    :db.unique/identity}
             :download/performed-at        {:db/valueType :db.type/instant}
             :download/origin-uri          {:db/valueType :db.type/string}
             })

(defn update-podcast!
  [conn {:keys [podcast/feed-uri] :as podcast}]
  (d/transact! conn [podcast]))

(defn save-episode!
  [conn {:keys [episode/url] :as episode-description}]
  (d/transact! conn [episode-description]))

(defn episode-by-url
  [db url]
  (d/entity db [:episode/url url]))

(def known-episode? (comp episode-by-url boolean))

(defn known-podcasts
  [conn]
  (->>
    (d/q '[:find (pull ?e [:podcast/feed-uri
                           :podcast/title
                           :podcast/icon-uri
                           :podcast/description])
           :in $
           :where [?e :podcast/feed-uri _]]
      (d/db conn))
    (map first)))

(defn newest-un-downloaded-episode-urls
  [conn]
  (->> (d/q '[:find ?time ?url
              :in $
              :where
              [?e :episode/url ?url]
              [?e :episode/publish-date ?time]
              (or [(missing? $ ?e :episode/saved-audio-uuid)]
                [(missing? $ ?e :episode/saved-thumbnail-uuid)]
                [(missing? $ ?e :episode/saved-video-uuid)])]
         (d/db conn))
    (sort-by first)
    (map second)))


(comment
  (save-episode! conn
    #:podcast{:title       "category/brokenomics",
              :icon-uri    "https://www.lotuseaters.com/logo.svg",
              :feed-uri    "https://www.lotuseaters.com/feed/category_brokenomics",
              :updated-at  "2023-11-02T23:40:22+00:00",
              :description "Latest category_brokenomics posts from The Lotus Eaters",
              :id          "https://www.lotuseaters.com/feed/category_brokenomics"})

  (let [eps (personal-rss-feed.ingest.lotus-eaters/rss-str->episodes (:body personal-rss-feed.ingest.lotus-eaters/resp))
        eps (map #(assoc % :episode/podcast "https://www.lotuseaters.com/feed/category_brokenomics") (:episodes eps))]
    (d/transact! conn eps))

  (d/transact! conn [{:episode/url              "https://www.lotuseaters.com/premium-brokenomics-21-or-investing-part-ii-with-peter-lawery-16-05-23"
                      :episode/saved-audio-uuid #uuid "079e0c02-8d23-4357-a52d-04b8f4d14fed"}])

  (newest-un-downloaded-episode-urls conn)

  (known-podcasts conn))



(defmethod ig/init-key ::conn
  [_ {:keys [uri] :as options}]
  (d/get-conn uri schema))

(defmethod ig/suspend-key! ::conn
  [_ _])

(defmethod ig/resume-key ::conn
  [_ _ _ _])

(defmethod ig/halt-key! ::conn
  [_ conn]
  (d/close conn))
