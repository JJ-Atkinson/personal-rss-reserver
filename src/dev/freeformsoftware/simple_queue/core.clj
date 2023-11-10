(ns dev.freeformsoftware.simple-queue.core
  "Simple in memory queue service with disk backup. Includes 
  
   - Writing each task as EDN to disk in the event of system shutdown
   - Priority Queue
   - Configurable rate limiter
   - Retry with a limited failure count and reporting options
   - Admin api for viewing and editing queue contents
   
   This is fairly specific to my own needs (small data, low volume queueing, single machine, rate limits,
   extreme internal visibility), and I will likely not add additional features.
   
   
   Data is stored in `persistence-dir` as follows:
   
   persistence-dir/queued-items/{uuid}.edn 
         Any queued item as an edn file. Maps to ::queue-item/item
   persistence-dir/{queue-name}.edn    
         A queue itself, except ::id->queued-item, ::rate-limit-fn are not present. Maps to ::queue/queue
   
   
   Queues must be setup by the program upon initialization. Multiple queue systems may operate in the same folder as long
   as they have different queue-item ids and different queue-names.
   "
  (:require
   [clojure.spec.alpha :as s]
   [dev.freeformsoftware.simple-queue.disk-backed-map :as disk-map]
   [dev.freeformsoftware.simple-queue.queue :as queue]
   [dev.freeformsoftware.simple-queue.queue-item :as queue-item]
   [com.fulcrologic.guardrails.core :refer [>defn => ?]]
   [chime.core :as chime])
  (:import (clojure.lang Atom)
           (java.time Instant Duration)
           (java.util Date)))

(s/def ::name->queue any?)                                  ;; Disk backed map
(s/def ::persistence-dir string?)
(s/def ::id->queue-item any?)                               ;; Disk backed map
(s/def ::default-timeout-ms number?)
(s/def ::default-retry-limit number?)
(s/def ::system #(instance? Atom %))

(defn- create-queue-item-map!
  [{::keys [persistence-dir mem-only?]}]
  (let [persistence-dir (str persistence-dir "/queued-items")]
    (disk-map/create-disk-backed-map!
      #::disk-map{:folder-str persistence-dir
                  :read-fn    (if mem-only? (constantly nil) (fn [k _] (queue-item/read! persistence-dir k)))
                  :write-fn   (if mem-only? (constantly nil) (fn [_ v] (queue-item/write! persistence-dir v)))})))

(defn- create-queue-map!
  [{::keys [persistence-dir queues mem-only?]}]
  (disk-map/create-disk-backed-map!
    #::disk-map{:folder-str   persistence-dir
                :initial-k->v (reduce (fn [acc q]
                                        (assoc acc (::queue/name q) q))
                                {}
                                queues)
                :read-fn      (partial queue/read! persistence-dir)
                :write-fn     (if mem-only? (constantly nil) (fn [_ v] (queue/write! persistence-dir v)))}))

(defn- retry-limit
  [system queue-item]
  (or (get queue-item ::queue-item/retry-limit)
    (get-in @system [::name->queue (::queue-item/queue queue-item) ::queue/default-retry-limit])
    (get @system ::default-retry-limit)
    0))

(defn default-timeout?-fn
  [ms]
  (fn [{::queue-item/keys [activation-time]}]
    (let [now-ms  (.getTime (Date.))
          then-ms (.getTime activation-time)]
      (> (- now-ms then-ms) ms))))

(defn- update!q
  "Specifically update a queue, optionally updating a sub-key"
  ([system name f] (swap! system update ::name->queue (fn [n-q] (update n-q name f))))
  ([system name key f] (swap! system update ::name->queue (fn [n-q] (update n-q name update key f)))))

(defn- update!qi
  ([system id f] (swap! system update ::id->queue-item (fn [n-q] (update n-q id f))))
  ([system id key f] (swap! system update ::id->queue-item (fn [n-q] (update n-q id update key f)))))

(defn- resolve!i
  [system id]
  (get-in @system [::id->queue-item id]))

(>defn -qsort!
  "Sort waiting-q, ensuring it's a vector. Low->High priority, suitable for peek/pop. Date new == low priority."
  [system queue-name]
  [::system ::queue/name => ::system]
  (let [sort (fn [q]
               (vec
                 (sort-by
                   #(let [qi (get-in @system [::id->queue-item %])]
                      [(::queue-item/priority qi) (- (.getTime (::queue-item/submission-time qi)))])
                   (set q))))]
    (update!q system queue-name ::queue/waiting-q sort)
    system))


(>defn qadd!
  "Add a new queue!"
  [system queue-ent]
  [::system
   (s/keys :req [::queue/name]
     :opt [::queue/rate-limit-fn
           ::queue/timeout?-fn])
   => ::system]
  (let [queue-ent (cond-> queue-ent
                    (and (not (contains? queue-ent ::queue/timeout?-fn))
                      (::default-timeout-ms @system))
                    (assoc ::queue/timeout?-fn (default-timeout?-fn (::default-timeout-ms @system))))]
    (assert (not (some (partial contains? queue-ent) [::queue/active-q ::queue/dead-q ::queue/waiting-q])))
    (update!q system (::queue/name queue-ent) #(merge queue-ent %))
    (-qsort! system (::queue/name queue-ent))
    system))

(>defn qsubmit!
  "Add an entry to an existing queue!"
  [system {::queue-item/keys [queue id data] :as queue-entry}]
  [::system
   (s/keys :req [::queue-item/id
                 ::queue-item/queue
                 ::queue-item/data]
     :opt [::queue-item/priority])
   => number?]
  (assert (contains? (set (keys (::name->queue @system))) queue))
  (let [entry (merge
                {::queue-item/status      ::queue-item/waiting
                 ::queue-item/retry-count 0
                 ::queue-item/priority    0}
                (assoc queue-entry ::queue-item/submission-time (Date.)))]
    (update!q system queue ::queue/waiting-q #(conj % id))
    (swap! system update ::id->queue-item assoc id entry)
    (-qsort! system queue)
    (count (get-in @system [::name->queue queue ::queue/waiting-q]))))

(>defn qview
  "Lazy view of the queued items. Items may be changed before resolved if the required items are not fully realized before 
   queue operations are made."
  [system queue-name]
  [::system ::queue/name => (s/coll-of ::queue-item/item)]
  (->> (get-in @system [::name->queue queue-name])
    (map #(resolve!i system %))))

(>defn qpeek!
  "Read the top entry off the queue. Nil if none is found. Respects ::queue/rate-limit-fn.
  
  
   sorted-waiting-reversed-lazy          is reversed so that about to be pulled queue items are first
   activated-items-lazy                  any currently activated items
   sorted-recent-queue-items-lazy        is sorted newest->oldest
  
   (fn rate-limit-fn [sorted-waiting-lazy-reversed activated-items-lazy sorted-recent-queue-items-lazy] 
     \"if true, then rate limit is active and no-one can peek/pop\")"
  [system queue-name]
  [::system ::queue/name => (? ::queue-item/item)]
  (let [queue-ent (get-in @system [::name->queue queue-name])]
    (when-let [possible-id (some-> queue-ent ::queue/waiting-q peek)]
      (if-let [rate-limit-f (::queue/rate-limit-fn queue-ent)]
        (let [sorted-waiting-reversed-lazy   (->> queue-ent ::queue/waiting-q reverse (map #(resolve!i system %)))
              activated-items-lazy           (->> queue-ent ::queue/active-q (map #(resolve!i system %)))
              sorted-recent-queue-items-lazy (->> queue-ent ::queue/dead-q (map #(resolve!i system %)))]
          (when-not (rate-limit-f sorted-waiting-reversed-lazy activated-items-lazy sorted-recent-queue-items-lazy)
            (get-in @system [::id->queue-item possible-id])))
        (get-in @system [::id->queue-item possible-id])))))

(>defn qpop!
  "Same as peek, but sets the entity as ::submitted"
  [system queue-name]
  [::system ::queue/name => (? ::queue-item/item)]
  (when-let [peekv (qpeek! system queue-name)]
    (let [ret   (assoc peekv
                  ::queue-item/activation-time (Date.)
                  ::queue-item/status ::queue-item/activated)
          qi-id (::queue-item/id peekv)]
      (update!q system queue-name #(-> % 
                                     (update ::queue/waiting-q pop)
                                     (update ::queue/active-q conj qi-id)))
      (update!qi system qi-id (fn [qi] (merge qi ret)))
      ret)))

(defn- -qfinish!*
  [system queue-item-id completion-data status]
  (let [{::queue-item/keys [queue] :as queue-ent} (resolve!i system queue-item-id)]
    (update!q system queue (fn [q]
                             (-> q
                               (update ::queue/waiting-q #(vec (remove (partial = queue-item-id) %)))
                               (update ::queue/active-q #(remove (partial = queue-item-id) %))
                               (update ::queue/dead-q #(cons queue-item-id %)))))
    (update!qi system queue-item-id
      (fn [qi]
        (assoc qi
          ::queue-item/completion-time (Date.)
          ::queue-item/completion-data completion-data
          ::queue-item/status status)))))

(defn -qerror-move!*
  "Move an item out of the active-q. If (::retryable? completion-data) and it is within the retry limit, the item
   is sent back to the waiting-q. Otherwise, it is marked dead."
  [system queue-item-id completion-data]
  (let [item        (resolve!i system queue-item-id)
        retry-limit (retry-limit system item)
        queue-name  (::queue-item/queue item)]
    (update!q system queue-name ::queue/active-q #(remove (partial = queue-item-id) %))
    (if (and (::retryable? completion-data)
          (> retry-limit (or (::queue-item/retry-count item) 0)))
      (qsubmit! system (assoc item
                         ::queue-item/retry-count ((fnil inc 0) (::queue-item/retry-count item))
                         ::queue-item/status ::queue-item/error-retrying
                         ::queue-item/priority Long/MAX_VALUE))
      (-qfinish!* system queue-item-id completion-data ::queue-item/failed))))

(>defn qcomplete!
  ([system queue-item-id]
   [::system ::queue-item/id => number?]
   (qcomplete! system queue-item-id {}))
  ([system queue-item-id completion-data]
   [::system ::queue-item/id ::queue-item/completion-data => number?]
   (-qfinish!* system queue-item-id completion-data ::queue-item/succeeded)))

(>defn qerror!
  "Mark a task as errored-out. Optionally can be marked as retryable? true to re-submit to the top of the queue."
  [system queue-item-id {::keys [retryable?] :as error-info}]
  [::system ::queue-item/id ::queue-item/completion-data => number?]
  (-qerror-move!* system queue-item-id error-info))

(defn -activate-timeout!
  "Activate the timeout on an item in the active-q. Optionally runs `notify-timed-out!`. If the item exceeds `retry-limit` (default 0),
   then it is kicked to the dead-q. If not, it is sent back to the active-q with Long/MAX_VALUE priority."
  [system queue-item-id]
  (let [i (resolve!i system queue-item-id)]
    (when-let [notify-timed-out! (or (get-in @system [::name->queue (::queue/name i) ::queue/notify-timed-out!])
                                   (::default-notify-timed-out! @system))]
      (notify-timed-out! i))
    (-qerror-move!* system queue-item-id {::retryable? true})))

(defn- prune-timeouts!
  [system]
  (let [queue-names        (keys (::name->queue @system))
        questionable-items (mapcat
                             (fn [qn]
                               (let [{::queue/keys [active-q] :as queue} (get-in @system [::name->queue qn])]
                                 (map #(merge queue (resolve!i system %)) active-q)))
                             queue-names)]
    (doseq [{::queue/keys [timeout?-fn] ::queue-item/keys [id] :as queue-item} questionable-items]
      (when (and timeout?-fn (timeout?-fn queue-item))
        (-activate-timeout! system id)))))

(defn- start-timeout-watchdog!
  [system]
  (swap! system assoc ::chime-ent (chime/chime-at
                                    (chime/periodic-seq (Instant/now) (Duration/ofMillis (get @system ::watchdog-ms 1500)))
                                    (fn [t] (prune-timeouts! system)))))

(>defn create-system!
  [{::keys [persistence-dir
            mem-only?
            default-timeout-ms] :as ins}]
  [(s/keys :req [::persistence-dir]) => any?]
  (let [system (atom (merge ins
                       {::name->queue    (create-queue-map! ins)
                        ::id->queue-item (create-queue-item-map! ins)}))]
    (start-timeout-watchdog! system)
    system))

(defn close-system!
  [system]
  (some-> @system ::chime-ent (.close)))

(comment
  (def s
    (create-system! {::persistence-dir           "/home/jarrett/code/personal/personal-rss-reserver/simple-queue"
                     ::default-timeout-ms        10000
                     ::default-retry-limit       2
                     ::watchdog-ms               150
                     ::default-notify-timed-out! (partial println "ent timed out!")}))

  @s
  (-qsort! s :third/queue)

  (qadd! s {::queue/name :third/queue})

  (do

    (qsubmit! s {::queue-item/id    #uuid"11111111-b2fc-4e21-aa6d-67dc48e9fbfd"
                 ::queue-item/queue :third/queue
                 ::queue-item/data  {:data "Med Prio, Add first"}})
    (Thread/sleep 1)
    (qsubmit! s {::queue-item/id    #uuid"22222222-b2fc-4e21-aa6d-67dc48e9fbfd"
                 ::queue-item/queue :third/queue
                 ::queue-item/data  {:data "Low Prio, Add second"}})
    (Thread/sleep 2)
    (qsubmit! s {::queue-item/id       #uuid"00000000-b2fc-4e21-aa6d-67dc48e9fbfd"
                 ::queue-item/priority 1
                 ::queue-item/queue    :third/queue
                 ::queue-item/data     {:data "High Prio, first out (actually tho)"}}))

  (qpeek! s :third/queue)
  (qpop! s :third/queue)

  (qcomplete! s #uuid"22222222-b2fc-4e21-aa6d-67dc48e9fbfd" {:best :success :ever! :yay})
  (qerror! s #uuid"11111111-b2fc-4e21-aa6d-67dc48e9fbfd" {:error :some-random-error
                                                          ::retryable? true})
  (prune-timeouts! s)

  (resolve!i s #uuid"11111111-b2fc-4e21-aa6d-67dc48e9fbfd")




  (swap! s update ::id->queue-item assoc #uuid"44444444-b2fc-4e21-aa6d-67dc48e9fbfd"
    {::queue-item/id              #uuid"44444444-b2fc-4e21-aa6d-67dc48e9fbfd"
     ::queue-item/submission-time (Date.)
     ::queue-item/queue           :initial/queue
     ::queue-item/status          ::queue-item/waiting
     ::queue-item/retry-count     0})

  (get-in @s [::id->queue-item #uuid"44444443-b2fc-4e21-aa6d-67dc48e9fbfd"]))
