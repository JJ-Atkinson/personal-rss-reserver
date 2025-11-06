(ns personal-rss-feed.feed.db
  (:require
   [datalevin.core :as d]
   [integrant.core :as ig]))

(defonce !conn (atom nil))

(def schema
  {:user/uname                          {:db/valueType :db.type/string
                                         :db/unique    :db.unique/identity}
   :user/password-crypt                 {:db/valueType :db.type/string}
   :user/admin?                         {:db/valueType :db.type/boolean}

   :episode/url                         {:db/valueType :db.type/string
                                         :db/unique    :db.unique/identity}
   :episode/id                          {:db/valueType :db.type/string} ;; One of :singleton/current-id
   :episode/uuid                        {:db/valueType :db.type/uuid}   ;; A uuid for naming in the s3 bucket,
   ;; enabling a passwordless CDN
   :episode/title                       {:db/valueType :db.type/string}
   :episode/ep-number                   {:db/valueType :db.type/string} ;; OPTIONAL!!
   :episode/thumbnail-origin-uri        {:db/valueType :db.type/string}
   :episode/excerpt                     {:db/valueType :db.type/string}
   :episode/publish-date                {:db/valueType :db.type/instant}
   :episode/audio-original-uri          {:db/valueType :db.type/string}
   :episode/video-original-uri          {:db/valueType :db.type/string}
   :episode/podcast                     {:db/valueType :db.type/string}
   :episode/audio-content-length        {:db/valueType :db.type/long} ;; only present when the podcast has been
   ;; downloaded properly.
   :episode/video-content-length        {:db/valueType :db.type/long} ;; only present when the podcast has been
   ;; downloaded properly.

   :podcast/feed-uri                    {:db/valueType :db.type/string
                                         :db/unique    :db.unique/identity} ;; Must be seeded by a user
   :podcast/id                          {:db/valueType :db.type/string} ;; One of :singleton/current-id
   :podcast/title                       {:db/valueType :db.type/string}
   :podcast/icon-uri                    {:db/valueType :db.type/string}
   :podcast/generated-icon-relative-uri {:db/valueType :db.type/string}
   :podcast/description                 {:db/valueType :db.type/string}

   :singleton/singleton-id              {:db/valueType :db.type/string
                                         :db/unique    :db.unique/identity} ;; Always going to be "1" so there's
   ;; only one in the db
   :singleton/current-id                {:db/valueType :db.type/string}})

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
    (d/transact! conn
                 [{:singleton/singleton-id "1"
                   :singleton/current-id   next-id}])
    next-id))

(defn episode-by-url
  [db url]
  (d/entity db [:episode/url url]))

(defn episode-by-id
  [conn id]
  (some->> (d/q '[:find ?feed-uri
                  :in $ ?id
                  :where
                  [?e :episode/id ?id]
                  [?e :episode/url ?feed-uri]]
                (d/db conn)
                id)
           (ffirst)
           (episode-by-url (d/db conn))))

(def known-episode? (comp boolean episode-by-url))

(defn podcast-by-feed-uri
  [db feed-uri]
  (d/entity db [:podcast/feed-uri feed-uri]))

(def known-podcast? (comp boolean podcast-by-feed-uri))

(defn save-episode!
  [conn {:keys [episode/url] :as episode-description}]
  (d/transact! conn
               [(cond-> episode-description
                  (not (known-episode? (d/db conn) url)) (assoc :episode/id   (next-id conn)
                                                                :episode/uuid (random-uuid)))]))

(defn save-podcast!
  [conn {:keys [podcast/feed-uri] :as podcast}]
  (d/transact! conn
               [(cond-> podcast
                  (not (known-podcast? (d/db conn) feed-uri)) (assoc :podcast/id (next-id conn)))]))

(defn known-podcasts
  [conn]
  (->>
   (d/q '[:find
          (pull
           ?e
           [:podcast/feed-uri
            :podcast/id
            :podcast/title
            :podcast/icon-uri
            :podcast/description
            :podcast/generated-icon-relative-uri])
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
        (d/db conn)
        feed-uri)
   (map #(d/entity (d/db conn) [:episode/url (first %)]))
   (sort-by :episode/publish-date)))

(defn podcast-by-id
  [conn id]
  (some->> (d/q '[:find ?feed-uri
                  :in $ ?id
                  :where
                  [?e :podcast/id ?id]
                  [?e :podcast/feed-uri ?feed-uri]]
                (d/db conn)
                id)
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
  (reset! !conn (d/get-conn "/home/jarrett/code/personal/personal-rss-reserver/.prod-data/datalevin" schema))

  (save-podcast! @!conn
                 {:podcast/feed-uri                    "https://lotuseaters.com/feed/category/health"
                  :podcast/generated-icon-relative-uri ""})
  (d/touch (d/entity (d/db @!conn)
                     [:episode/url
                      "https://www.lotuseaters.com/premium-live-lads-hour-12-or-zombie-apocalypse-21-11-2023"]))
  (d/touch (d/entity (d/db @!conn) [:podcast/id "https://www.lotuseaters.com/feed/category/aaab"]))
  (known-podcasts @!conn)
  (known-podcast? (d/db @!conn) "test")
  (map :episode/url (podcast-feed @!conn "https://lotuseaters.com/feed/category/symposium"))

  (d/touch (podcast-by-id @!conn "aaab"))
  (d/touch (episode-by-id @!conn "aahf"))

  ;; Add a user
  (d/transact! @!conn
               [{:user/uname          "jarrett"
                 :user/password-crypt (personal-rss-feed.admin.auth/generate-password-crypt
                                       @personal-rss-feed.admin.auth/!config
                                       "password")
                 :user/admin?         true}])

  (d/touch (d/entity (d/db @!conn) [:user/uname "jarrett"]))

  (->>
   (d/q '[:find ?url
          :in $
          :where [?e :episode/url ?url]]
        (d/db @!conn))
   (map first)
   (map #(d/entity (d/db @!conn) [:episode/url %]))
   (map #(select-keys %
                      [:episode/url
                       :episode/publish-date
                       :episode/title
                       :episode/podcast
                       :episode/audio-content-length]))
   (sort-by :episode/publish-date)
   (filter :episode/audio-content-length)
   (count)))
