(ns dev.freeformsoftware.simple-queue.disk-backed-map
  "TODO: this probably makes more sense to use the datalevin KV store functionality for EDN..."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [potemkin :as pk]
   [taoensso.encore :as enc])
  (:import (java.nio.file Files Paths)
           (java.nio.file.attribute FileAttribute)))

;; THIS IS NOT A PURE MAP. It behaves somewhat like a transient. Every `assoc` is a disk write, and 
;; every `get` is either a read from disk read from cache. dissoc does nothing. default-value for get does nothing
;; `keys` can be seeded with an initial set for easier traversal.
;; k-pred should return true for any expected key, and nil otherwise. the repl will do sneaky things like 
;; (get ... :type) to try and learn more info, but those need to be blocked.
(pk/def-map-type DiskBackedMap [!keys-cache !vals-cache k-pred read-fn write-fn]
  (get [_ k default-value]
    (if-not (k-pred k)
      default-value
      (if (contains? @!vals-cache k)
        (get @!vals-cache k)
        (let [v (read-fn k nil)]
          (swap! !keys-cache conj k)
          (swap! !vals-cache assoc k v)
          v))))
  (assoc [_ k v]
    (write-fn k v)
    (swap! !vals-cache assoc k v)
    (swap! !keys-cache conj k)
    _)
  (dissoc [_ k] _)
  (keys [_]
    @!keys-cache)
  (meta [_] nil)
  (with-meta [_ mta] _))

(defn create-disk-backed-map!
  "Keys MAY ONLY BE STRINGS, UUIDs, OR PRE-SEEDED. Printing this to the repl is challenging and has led to a few 
   weird bugs where the repl queries :type and class instance, so I've just nuked any key that's _not_ one of the above.
  
   key->file-name is only useful if you've used the default read-fn and write-fn, otherwise it is not used at all."
  [{::keys [folder-str
            key->file-name
            initial-k->v
            initial-keys
            read-fn
            write-fn]
    :or    {initial-keys #{}}}]
  (assert (and folder-str (string? folder-str)))
  (assert (or key->file-name (and read-fn write-fn)))
  (assert ((some-fn map? nil?) initial-k->v))
  (let [read-fn             (or read-fn (fn [k _] (->> k (key->file-name) (io/file folder-str) (slurp) (edn/read-string))))
        write-fn            (or write-fn (fn [k v]
                                           (spit (->> k (key->file-name) (io/file folder-str))
                                             (pr-str v))))
        initial-k->v-seeded (reduce-kv
                              (fn [m k v] (assoc m k (read-fn k v)))
                              {}
                              initial-k->v)
        initial-keyset      (into initial-keys (set (keys initial-k->v)))]
    (Files/createDirectories (Paths/get folder-str (make-array String 0)) (make-array FileAttribute 0))
    (->DiskBackedMap
      (atom initial-keyset)
      (atom initial-k->v-seeded)

      (constantly true)                                     ;; maybe I was wrong about k-pred being needed. See deftype comment above
      ;;(fn [k]
      ;;  (or (uuid? k) (string? k) (contains? initial-keyset k)))
      read-fn
      write-fn))) 
