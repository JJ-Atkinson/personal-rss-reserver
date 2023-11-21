(ns personal-rss-feed.feed.db
  (:require
   [datalevin.core :as d]
   [integrant.core :as ig]))

(defonce !conn (atom nil))

(def schema {:aka                                 {:db/cardinality :db.cardinality/many}
             ;; :db/valueType is optional, if unspecified, the attribute will be
             ;; treated as EDN blobs, and may not be optimal for range queries
             :name                                {:db/valueType :db.type/string
                                                   :db/unique    :db.unique/identity}

             :episode/url                         {:db/valueType :db.type/string
                                                   :db/unique    :db.unique/identity}
             :episode/id                          {:db/valueType :db.type/string} ;; One of :singleton/current-id, used in the electric app
             :episode/uuid                        {:db/valueType :db.type/uuid} ;; A uuid for naming in the s3 bucket, enabling a passwordless CDN
             :episode/title                       {:db/valueType :db.type/string}
             :episode/ep-number                   {:db/valueType :db.type/string} ;; OPTIONAL!!
             :episode/thumbnail-origin-uri        {:db/valueType :db.type/string}
             :episode/excerpt                     {:db/valueType :db.type/string}
             :episode/publish-date                {:db/valueType :db.type/instant}
             :episode/audio-original-uri          {:db/valueType :db.type/string}
             :episode/video-original-uri          {:db/valueType :db.type/string}
             :episode/podcast                     {:db/valueType :db.type/string}
             :episode/audio-content-length        {:db/valueType :db.type/long} ;; only present when the podcast has been downloaded properly.
             :episode/video-content-length        {:db/valueType :db.type/long} ;; only present when the podcast has been downloaded properly.

             :podcast/feed-uri                    {:db/valueType :db.type/string
                                                   :db/unique    :db.unique/identity} ;; Must be seeded by a user
             :podcast/id                          {:db/valueType :db.type/string} ;; One of :singleton/current-id
             :podcast/title                       {:db/valueType :db.type/string}
             :podcast/icon-uri                    {:db/valueType :db.type/string}
             :podcast/generated-icon-relative-uri {:db/valueType :db.type/string}
             :podcast/description                 {:db/valueType :db.type/string}

             :singleton/singleton-id              {:db/valueType :db.type/string
                                                   :db/unique    :db.unique/identity} ;; Always going to be "1" so there's only one in the db
             :singleton/current-id                {:db/valueType :db.type/string}
             })

(defn inc-str
  "Take a character string (e.g. aaa) and increment it, (e.g. aab)."
  [s]
  (let [inc (fn [c]
              (let [new    (inc (int c))
                    carry? (> new (int \z))]
                [carry? (if carry? \a (char new))]))]
    (->> s
      (reverse)
      (reduce (fn [[res carry?] c]
                (let [[carry? new] (if carry? (inc c) [false c])]
                  [(cons new res) carry?]))
        [[] true])
      (first)
      (apply str))))

(defn next-id
  [conn]
  (let [current-id (or (ffirst (d/q '[:find ?s
                                      :in $
                                      :where
                                      [?e :singleton/singleton-id "1"]
                                      [?e :singleton/current-id ?s]]
                                 (d/db conn)))
                     "aaaa")
        next-id    (inc-str current-id)]
    (d/transact! conn [{:singleton/singleton-id "1"
                        :singleton/current-id   next-id}])
    next-id))

(defn episode-by-url
  [db url]
  (d/entity db [:episode/url url]))

(def known-episode? (comp boolean episode-by-url))

(defn podcast-by-feed-uri
  [db feed-uri]
  (d/entity db [:podcast/feed-uri feed-uri]))

(def known-podcast? (comp boolean podcast-by-feed-uri))

(defn save-episode!
  [conn {:keys [episode/url] :as episode-description}]
  (d/transact! conn [(cond-> episode-description
                       (not (known-episode? (d/db conn) url)) (assoc :episode/id (next-id conn)
                                                                     :episode/uuid (random-uuid)))]))

(defn save-podcast!
  [conn {:keys [podcast/feed-uri] :as podcast}]
  (d/transact! conn [(cond-> podcast
                       (not (known-podcast? (d/db conn) feed-uri)) (assoc :podcast/id (next-id conn)))]))

(defn known-podcasts
  [conn]
  (->>
    (d/q '[:find (pull ?e [:podcast/feed-uri
                           :podcast/id
                           :podcast/title
                           :podcast/icon-uri
                           :podcast/description])
           :in $
           :where [?e :podcast/feed-uri _]]
      (d/db conn))
    (map first)))

(defn podcast-feed
  "Returns potentially un-downloaded episodes. Filter entities by (contains? e :episode/audio-content-length)."
  [conn feed-uri]
  (->>
    (d/q '[:find ?ep-url
           :in $ ?podcast-uri
           :where
           [?e :episode/podcast ?podcast-uri]
           [?e :episode/url ?ep-url]]
      (d/db conn) feed-uri)
    (map #(d/entity (d/db conn) [:episode/url (first %)]))
    (sort-by :episode/publish-date)))

(defn podcast-by-id
  [conn id]
  (some->> (d/q '[:find ?feed-uri
                  :in $ ?id
                  :where
                  [?e :podcast/id ?id]
                  [?e :podcast/feed-uri ?feed-uri]]
             (d/db conn) id)
    (ffirst)
    (podcast-by-feed-uri (d/db conn))))


(defmethod ig/init-key ::conn
  [_ {:keys [uri] :as options}]
  (reset! !conn (d/get-conn uri schema)))

(defmethod ig/suspend-key! ::conn
  [_ _])

(defmethod ig/resume-key ::conn
  [_ _ _ _])

(defmethod ig/halt-key! ::conn
  [_ conn]
  (d/close conn))

(comment
  (save-podcast! @!conn {:podcast/feed-uri "https://www.lotuseaters.com/feed/category/epochs"})
  (d/touch (d/entity (d/db @!conn) [:episode/url "https://www.lotuseaters.com/premium-live-lads-hour-10-or-birthday-special-09-11-2023"]))
  (d/touch (d/entity (d/db @!conn) [:podcast/id "https://www.lotuseaters.com/feed/category/aaab"]))
  (known-podcasts @!conn)
  (known-podcast? (d/db @!conn) "test")
  (map d/touch
    (podcast-feed @!conn "https://www.lotuseaters.com/feed/category/epochs"))
  (d/touch (podcast-by-id @!conn "aaab"))

  (->>
    (d/q '[:find ?url
           :in $
           :where [?e :episode/url ?url]]
      (d/db @!conn))
    (map first)))
