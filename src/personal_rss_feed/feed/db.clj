(ns personal-rss-feed.feed.db
  (:require
   [asami.core :as d]))

(comment
  ;; Create an in-memory database, named dbname
  (def db-uri "asami:mem://dbname")
  (d/create-database db-uri)

  ;; Create a connection to the database
  (def conn (d/connect db-uri))

  ;; Data can be loaded into a database either as objects, or "add" statements:
  (def first-movies [{:movie/title "Explorers"
                      :movie/genre "adventure/comedy/family"
                      :movie/release-year 1985}
                     {:movie/title "Demolition Man"
                      :movie/genre "action/sci-fi/thriller"
                      :movie/release-year 1993}
                     {:movie/title "Johnny Mnemonic"
                      :movie/genre "cyber-punk/action"
                      :movie/release-year 1995}
                     {:movie/title "Toy Story"
                      :movie/genre "animation/adventure"
                      :movie/release-year 1995}])

  @(d/transact conn {:tx-data first-movies})

  (def db (d/db conn))

  (d/q '[:find [?movie-title ...]
         :where [?m :movie/title ?movie-title]] db)
  (d/q '[:find [?m ...] :where [?m :movie/release-year 1995]] db))

(def db-uri "asami:mem://dbname")
(defonce conn (d/connect db-uri))

(defn known-episode?
  [conn ep-url]
  (first (d/q '[:find [?e ...]
                :in $ ?url
                :where
                [?e :episode/url ?url]]
           (d/db conn)
           ep-url)))

(defn make-single-arity-of
  [m]
  (into {}
    (map (fn [[k v]]
           [(keyword (str (subs (str k) 1) \')) v]))
    m))

(defn save-episode!
  [conn {:keys [episode/url] :as episode-description}]
  (d/transact conn {:tx-data [(assoc (make-single-arity-of episode-description)
                                     (if (known-episode? conn url)
                                       :id
                                       :db/id) url)]}))

(save-episode! 
  conn 
  {:episode/url "whatev"
   :something-something "a"})
