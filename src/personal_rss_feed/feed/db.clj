(ns personal-rss-feed.feed.db
  (:require
   [datalevin.core :as d]))

(def schema {:aka {:db/cardinality :db.cardinality/many}
             ;; :db/valueType is optional, if unspecified, the attribute will be
             ;; treated as EDN blobs, and may not be optimal for range queries
             :name {:db/valueType :db.type/string
                    :db/unique :db.unique/identity}

             :episode/url {:db/valueType :db.type/string
                           :db/unique :db.unique/identity}
             :episode/title {:db/valueType :db.type/string}
             :episode/ep-number {:db/valueType :db.type/string}
             :episode/thumbnail-origin-uri {:db/valueType :db.type/string}
             :episode/excerpt {:db/valueType :db.type/string}
             :episode/series {:db/valueType :db.type/string}
             :episode/audio-original-uri {:db/valueType :db.type/string}})

(def conn (d/get-conn "/tmp/datalevin/mydb" schema))

(defn save-episode!
  [conn {:keys [episode/url] :as episode-description}]
  (d/transact! conn [episode-description]))

(defn episode-by-url
  [db url]
  (d/entity db [:episode/url url]))

(def known-episode? (comp episode-by-url boolean))
