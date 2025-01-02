(ns personal-rss-feed.config
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [taoensso.timbre :as log]))

(defn nref?
  [x]
  (and (map? x) (contains? x ::nref)))

(defn deep-merge
  "Deep merge maps, with understanding that an nref is a scalar not a map."
  [a b]
  (if (or (nref? a) (nref? b))
    b ;; When a value is scalar (nref), the second value always wins
    (merge-with (fn [x y]
                  (if (and (map? x) (map? y)) (deep-merge x y) y))
                a
                b)))

(defn read-config-files!
  [enable-prod?]
  (keep (fn [s]
          (try
            (slurp s)
            (catch Exception e
              (when enable-prod?
                (log/error "Could not read config file!" s e))
              nil)))
        [(io/resource "config/config.edn")
         ;; not available in prod since it's not part of the jar build. flakes ftw!
         (when-not enable-prod? (io/resource "config/secrets.edn"))
         (when enable-prod? (io/file "/etc/rss-feed-config.edn"))
         (when enable-prod? (io/file "/etc/rss-feed-secrets.edn"))]))

(defn reader-nref
  [key]
  (assert (or (keyword? key)
              (and (vector? key)
                   (every? keyword? key))))
  {::nref (if (keyword? key) [key] key)})

(defn resolve-nrefs
  [config]
  (let [get-exists! (fn [path]
                      (let [m (get-in config (butlast path))]
                        (assert (and (map? m) (contains? m (last path))) (str "Unable to find key " path " in config!"))
                        (get m (last path))))]
    (walk/prewalk
     (fn [x]
       (if (nref? x)
         (get-exists! (::nref x))
         x))
     config)))

(defn resolve-config!
  [enable-prod?]
  (let [parsed-config
        (->> (read-config-files! enable-prod?)
             (map (partial ig/read-string {:readers {'n/ref reader-nref}}))
             (reduce deep-merge)
             (resolve-nrefs)
             :system)]
    (ig/load-namespaces parsed-config)
    parsed-config))
