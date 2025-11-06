(ns dev.freeformsoftware.simple-queue.core
  "Simple in memory queue service with disk backup. Includes 
  
   - Writing each task as EDN to disk in the event of system shutdown
   - Priority Queue
   - Configurable rate limiter
   - Retry with a limited failure count and reporting options
   - No back pressure, items should be able to accumulate forever
   - Admin api for viewing and editing queue contents
   
   This is fairly specific to my own needs (small data, low volume queueing, single machine, rate limits,
   extreme internal visibility, large task dumps), and I will likely not add additional features.
   
   
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
   [chime.core :as chime]
   [taoensso.timbre :as log])
  (:import (clojure.lang Atom)
           (java.time Instant Duration)
           (java.util Date)))

(s/def ::name->queue any?)                                  ;; Disk backed map
(s/def ::persistence-dir string?)
(s/def ::id->queue-item any?)                               ;; Disk backed map
(s/def ::default-timeout-ms number?)
(s/def ::default-retry-limit number?)
(s/def ::system #(instance? Atom %))

(defonce
  ^{:doc
    "For debugging, it can be useful to ratelimit to 0 and manually trigger dequeues. swap! conj onto the set
   the name of the queue you'd like to manually trigger."
    :dynamic true}
  *manual-unlock-1*
  (atom #{}))

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
               :read-fn      (if mem-only? (constantly nil) (partial queue/read! persistence-dir))
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

(defn comp-or_lockout_rate-limit
  "Compose lockout? fns or rate-limit? fns with (or), making them pessimistic. 
   If every limit is disabled, then the item will dequeue."
  [& lockouts-or-rate-limits]
  (fn comp-or_lockout_rate-limit* [& args]
    (boolean (some #(apply % args) lockouts-or-rate-limits))))

(defn comp-and_lockout_rate-limit
  "Compose lockout? fns or rate-limit? fns with (and), making them optimistic. 
   If any limit is disabled, then the item will dequeue."
  [& lockouts-or-rate-limits]
  (fn comp-and_lockout_rate-limit* [& args]
    (every? #(apply % args) lockouts-or-rate-limits)))

(defn rate-limit-number-active
  [max-active-tasks]
  (fn [_waiting-reversed active _completed]
    (<= max-active-tasks (count active))))

(defn update!q
  "Specifically update a queue, optionally updating a sub-key"
  ([system queue-name f] (swap! system update ::name->queue (fn [n->q] (update n->q queue-name f))))
  ([system queue-name key f] (swap! system update ::name->queue (fn [n->q] (update n->q queue-name update key f)))))

(defn update!qi
  ([system queue-item-id f] (swap! system update ::id->queue-item (fn [n-q] (update n-q queue-item-id f))))
  ([system queue-item-id key f]
   (swap! system update ::id->queue-item (fn [n-q] (update n-q queue-item-id update key f)))))

(defn resolve!i
  [system queue-item-id]
  (get-in @system [::id->queue-item queue-item-id]))

(let [item-id (atom nil)]
  (defn lockout?-intensive-cpu-tasks
    "A global singleton lockout fn that only allows a single cpu intensive task to be checked out at once. 
     Automatically closed when the open item is completed or times out."
    []
    (fn [system {new-id ::queue-item/id}]
      (when-let [current-item (resolve!i system @item-id)]
        (if (= ::queue-item/activated (::queue-item/status current-item))
          true
          (do (reset! item-id new-id) false))))))

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

(>defn qcreate!
  "Create a new queue!"
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
  (log/info "Submitting queue item" queue-entry)
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
  (->> (get-in @system [::name->queue queue-name ::queue/waiting-q])
       (mapv #(resolve!i system %))))

(>defn qview-dead
  "Lazy view of the dead items. Items may be changed before resolved if the required items are not fully realized before 
   queue operations are made."
  [system queue-name]
  [::system ::queue/name => (s/coll-of ::queue-item/item)]
  (->> (get-in @system [::name->queue queue-name ::queue/dead-q])
       (mapv #(resolve!i system %))
       (sort-by ::queue-item/completion-time)
       (vec)))

(>defn qview-active
  "Lazy view of the active items. Items may be changed before resolved if the required items are not fully realized before 
   queue operations are made."
  [system queue-name]
  [::system ::queue/name => (s/coll-of ::queue-item/item)]
  (->> (get-in @system [::name->queue queue-name ::queue/active-q])
       (map #(resolve!i system %))
       (sort-by ::queue-item/completion-time)
       (vec)))

(>defn qpeek!
  "Read the top non-locked entry off the queue. Nil if none is found. Respects rate-limit-fn and lockout?-fn. 
   Rate limit can be overridden by *manual-unlock-1* (see docstr), and lockout? can be overridden by ::lockout-override?
   set on the queue item.
  
  ::queue/rate-limit-fn
  
  (fn [
   sorted-waiting-reversed-lazy          ; is reversed so that about to be pulled queue items are first. not a vec so resolution is lazy
   activated-items-lazy                  ; any currently activated items
   sorted-recent-queue-items-lazy        ; is sorted newest->oldest
   ] boolean? ) ;; if true, then rate limit is active and no-one can peek/pop
  
   ::queue/lockout?-fn
   
   (fn [system queue-item] boolean?) 
   
   if present, when returns true the queue item is \"locked\", meaning the item is basically invisible in the waiting-q. 
   it will be passed over in favor of other lower priority entries. Useful if a specific lockout timer is required for
   retries on a queue. "
  [system queue-name]
  [::system ::queue/name => (? ::queue-item/item)]
  (let [queue-ent (get-in @system [::name->queue queue-name])
        lockout?  (::queue/lockout?-fn queue-ent)
       ]
    (when-let [possible-id (if lockout?
                             (some->> queue-ent
                                      ::queue/waiting-q
                                      reverse
                                      (map (partial resolve!i system))
                                      (remove #(and (lockout? system %)
                                                    (not (::lockout-override? %))))
                                      first
                                      ::queue-item/id)
                             (some-> queue-ent
                                     ::queue/waiting-q
                                     peek))]
      (if-let [rate-limit-f (::queue/rate-limit-fn queue-ent)]
        (let [rate-limit-f                   (fn [& args]
                                               (if (contains? @*manual-unlock-1* queue-name)
                                                 (do
                                                   (log/warn "Allowing manual dequeue!!")
                                                   (swap! *manual-unlock-1* disj queue-name)
                                                   false)
                                                 (apply rate-limit-f args)))
              sorted-waiting-reversed-lazy   (->> queue-ent ::queue/waiting-q reverse (map #(resolve!i system %)))
              activated-items-lazy           (->> queue-ent ::queue/active-q (map #(resolve!i system %)))
              sorted-recent-queue-items-lazy (->> queue-ent ::queue/dead-q (map #(resolve!i system %)))]
          (when-not (rate-limit-f sorted-waiting-reversed-lazy activated-items-lazy sorted-recent-queue-items-lazy)
            (resolve!i system possible-id)))
        (resolve!i system possible-id)))))

(>defn manual-dequeue!
  "Dequeue an existing item, regardless of priority, lockout, or rate-limit."
  [system queue-item-id]
  [::system ::queue-item/id => (? ::queue-item/item)]
  (when-let [ret (resolve!i system queue-item-id)]
    (let [ret (assoc ret
                     ::queue-item/activation-time (Date.)
                     ::queue-item/status          ::queue-item/activated)
          {::queue-item/keys [queue]} ret]
      (update!q system
                queue
                #(-> %
                     (update ::queue/waiting-q (fn [wq] (vec (remove (partial = queue-item-id) wq))))
                     (update ::queue/active-q conj queue-item-id)))
      (update!qi system queue-item-id (fn [qi] (merge qi ret)))
      ret)))

(>defn qpop!
  "Same as peek, but sets the entity as ::submitted"
  [system queue-name]
  [::system ::queue/name => (? ::queue-item/item)]
  (when-let [peekv (qpeek! system queue-name)]
    (manual-dequeue! system (::queue-item/id peekv))))

(defn- -qfinish!*
  [system queue-item-id completion-data status]
  (let [{::queue-item/keys [queue] :as queue-ent} (resolve!i system queue-item-id)]
    (update!q system
              queue
              (fn [q]
                (-> q
                    (update ::queue/waiting-q #(vec (remove (partial = queue-item-id) %)))
                    (update ::queue/active-q #(remove (partial = queue-item-id) %))
                    (update ::queue/dead-q #(cons queue-item-id %)))))
    (update!qi system
               queue-item-id
               (fn [qi]
                 (assoc qi
                        ::queue-item/completion-time (Date.)
                        ::queue-item/completion-data completion-data
                        ::queue-item/status          status)))))

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
      (qsubmit! system
                (assoc item
                       ::queue-item/retry-count     ((fnil inc 0) (::queue-item/retry-count item))
                       ::queue-item/status          ::queue-item/error-retrying
                       ::queue-item/priority        (dec Long/MAX_VALUE)
                       ::queue-item/completion-data {::failure-data completion-data}))
      (-qfinish!* system queue-item-id completion-data ::queue-item/failed))))

(>defn qcomplete!
  ([system queue-item-id]
   [::system ::queue-item/id => number?]
   (qcomplete! system queue-item-id {}))
  ([system queue-item-id completion-data]
   [::system ::queue-item/id ::queue-item/completion-data => number?]
   (log/info "Successfully completed queue-item!" (resolve!i system queue-item-id))
   (-qfinish!* system queue-item-id completion-data ::queue-item/succeeded)))

(>defn qerror!
  "Mark a task as errored-out. Optionally can be marked as retryable? true to re-submit to the top of the queue."
  [system queue-item-id {::keys [retryable?] :as error-info}]
  [::system ::queue-item/id ::queue-item/completion-data => number?]
  (log/error "Error processing queue item!"
             {:queue-item (resolve!i system queue-item-id)
              :error-info error-info})
  (-qerror-move!* system queue-item-id error-info))

(defn all-un-resolved-errors
  "Utility to help find un-resolved errors. A resolved error has the key ::simple-queue/resolved? true
   in the completion data."
  ([system]
   (mapcat #(all-un-resolved-errors system %) (keys (::name->queue system))))
  ([system queue-name]
   (filter #(and (= ::queue-item/failed (::queue-item/status %))
                 (not (::resolved? (::queue-item/completion-data %))))
           (qview-dead system queue-name))))

(defn resolve-error!
  "User metadata. Resolve an error. See `all-un-resolved-errors`."
  [system queue-item-id]
  (update!qi system queue-item-id ::queue-item/completion-data #(assoc % ::resolved? true))
  nil)

(defn qresubmit-item!
  "Resubmit an item that has previously failed, resetting the retry counter and completion data. Adds a flag indicating re-submission to ::queue-item/data
   By default, assigns max priority."
  ([system queue-item-id]
   (qresubmit-item! system queue-item-id true))
  ([system queue-item-id max-priority?]
   (if-let [qi (resolve!i system queue-item-id)]
     (qsubmit! system
               (-> qi
                   (dissoc ::queue-item/retry-count ::queue-item/completion-data)
                   (cond-> max-priority? (assoc ::queue-item/priority Long/MAX_VALUE))
                   (update ::queue-item/data assoc ::prior-failure-data (::queue-item/completion-data qi))
                   (update ::queue-item/data assoc ::resubmitted? true)))
     (throw (ex-info "Does not exist!" {:queue-item-id queue-item-id})))))

(defn -activate-timeout!
  "Activate the timeout on an item in the active-q. Optionally runs `notify-timed-out!`. If the item exceeds `retry-limit` (default 0),
   then it is kicked to the dead-q. If not, it is sent back to the active-q with Long/MAX_VALUE-1 priority."
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
        (log/info "Queue item timed out!" queue-item)
        (-activate-timeout! system id)))))

(defn- start-timeout-watchdog!
  [system]
  #_
    (swap! system assoc
      ::chime-ent
      (chime/chime-at
       (chime/periodic-seq (Instant/now) (Duration/ofMillis (get @system ::watchdog-ms 1500)))
       (fn [t] (prune-timeouts! system)))))

(>defn create-system!
  [{::keys [persistence-dir
            mem-only?
            default-timeout-ms]
    :as    ins}]
  [(s/keys :req [::persistence-dir]) => any?]
  (let [system (atom (merge ins
                            {::name->queue    (create-queue-map! ins)
                             ::id->queue-item (create-queue-item-map! ins)}))]
    (start-timeout-watchdog! system)
    system))

(defn close-system!
  [system]
  (some-> @system
          ::chime-ent
          (.close)))

(comment
  (def s
    (create-system! {::persistence-dir           "/home/jarrett/code/personal/personal-rss-reserver/simple-queue"
                     ::default-timeout-ms        10000
                     ::default-retry-limit       2
                     ::watchdog-ms               150
                     ::default-notify-timed-out! (partial println "ent timed out!")}))

  @s
  (-qsort! s :third/queue)

  (qcreate! s
            {::queue/name        :third/queue
             ::queue/lockout?-fn #(get-in %2 [::queue-item/data :locked-eternally?])})

  (do

    (qsubmit! s
              {::queue-item/id    #uuid "11111111-b2fc-4e21-aa6d-67dc48e9fbfd"
               ::queue-item/queue :third/queue
               ::queue-item/data  {:data "Med Prio, Add first"}})
    (Thread/sleep 1)
    (qsubmit! s
              {::queue-item/id    #uuid "22222222-b2fc-4e21-aa6d-67dc48e9fbfd"
               ::queue-item/queue :third/queue
               ::queue-item/data  {:data              "Low Prio, Add second"
                                   :locked-eternally? true}})
    (Thread/sleep 2)
    (qsubmit! s
              {::queue-item/id       #uuid "00000000-b2fc-4e21-aa6d-67dc48e9fbfd"
               ::queue-item/priority 1
               ::queue-item/queue    :third/queue
               ::queue-item/data     {:data "High Prio, first out (actually tho)"}}))

  (qpeek! s :third/queue)
  (qpop! s :third/queue)
  (qview s :third/queue)
  (manual-dequeue! s #uuid "22222222-b2fc-4e21-aa6d-67dc48e9fbfd")

  (qview-active s :third/queue)

  (qcomplete! s #uuid "22222222-b2fc-4e21-aa6d-67dc48e9fbfd" {:best :success :ever! :yay})
  (qerror! s
           #uuid "11111111-b2fc-4e21-aa6d-67dc48e9fbfd"
           {:error       :some-random-error
            ::retryable? true})
  (prune-timeouts! s)

  (resolve!i s #uuid "11111111-b2fc-4e21-aa6d-67dc48e9fbfd")

  (swap! s update
    ::id->queue-item
    assoc
    #uuid "44444444-b2fc-4e21-aa6d-67dc48e9fbfd"
    {::queue-item/id              #uuid "44444444-b2fc-4e21-aa6d-67dc48e9fbfd"
     ::queue-item/submission-time (Date.)
     ::queue-item/queue           :initial/queue
     ::queue-item/status          ::queue-item/waiting
     ::queue-item/retry-count     0})

  (get-in @s [::id->queue-item #uuid "44444443-b2fc-4e21-aa6d-67dc48e9fbfd"]))
