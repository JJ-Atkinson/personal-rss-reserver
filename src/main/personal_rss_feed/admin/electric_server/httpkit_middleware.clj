(ns personal-rss-feed.admin.electric-server.httpkit-middleware

  "Provide a `wrap-electric-websocket` HTTPKit compatible middleware, starting and
  managing an Electric Server. This is a variant of
  `hyperfiddle.electric-ring-adapter` made compatible with HTTPKit."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [contrib.assert :refer [check]]
   [hiccup.page :refer [html5]]
   [hyperfiddle.electric :as e]
   [hyperfiddle.electric-ring-adapter :as ering]
   [org.httpkit.server :as httpkit]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.defaults :refer [wrap-defaults]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.util.response :as res]
   [ring.websocket :as ws])
  (:import
    (org.httpkit.server AsyncChannel)))

(defrecord HTTPKitSocket [^AsyncChannel channel]
  ering/Socket
    (open? [_] (httpkit/open? channel))
    (close [_this code] (.serverClose channel code))
    (close [_this code _reason] (.serverClose channel code)) ; HTTPKit doesn't support close reason
    (send [_this value] (httpkit/send! channel {:body value}))
    (send [_this value success-cb failure-cb]
      (if (httpkit/send! channel {:body value})
        (success-cb)
        (failure-cb (ex-info "Can't send message to client, remote channel is closed" {}))))

  ;; ping and pong are not exposed by HTTPKit. Instead ping is replaced by a
  ;; special "HEARTBEAT" message that the client will echo. HTTPKit will
  ;; automatically answer client pings with an immediate echo pong.
  ering/Pingable
    (ping [this] (ering/ping this "HEARTBEAT"))
    (ping [this value] (assert (= "HEARTBEAT" value)) (ering/send this value))
    (pong [this] (throw (ex-info "Pong is not supported" {})))
    (pong [this value] (throw (ex-info "Pong with arbitrary data is not supported" {})))
)

(defn reject-websocket-handler
  "Will accept socket connection upgrade and immediately close the socket on
  connection, with given `code` and `reason`. Use this to cleanly reject a
  websocket connection."
  ;; Rejecting the HTTP 101 Upgrade request would also prevent the socket to
  ;; open, but for security reasons, the client is never informed of the HTTP
  ;; 101 failure cause.
  [code reason]
  {:on-open (fn [socket] (ering/close (HTTPKitSocket. socket) code reason))})

(def STATUS-CODE
  "Map HTTPKit custom WS status names to the actual RFC-defined status code, if it
  can be mapped. Fully qualify the status name otherwise."
  {:server-close          ::server-close
   :client-close          ::client-close
   :normal                1000
   :going-away            1001
   :protocol-error        1002
   :unsupported           1003
   :no-status-received    1005
   :abnormal              1006
   :invalid-payload-data  1007
   :policy-violation      1008
   :message-too-big       1009
   :mandatory-extension   1010
   :internal-server-error 1011
   :tls-handshake         1015
   :unknown               ::unknown})

(defmethod ering/handle-close-status-code ::server
  [_ring-req _socket _status-code & [_reason]]
  (log/debug "HTTPKit server closed the websocket connection"))

(defmethod ering/handle-close-status-code ::client
  [_ring-req _socket _status-code & [_reason]]
  (log/debug "Websocket client closed the connection for an unknown reason"))

(defmethod ering/handle-close-status-code ::unknown
  [_ring-req _socket _status-code & [_reason]]
  (log/debug "HTTPKit websocket connection closed for an unknown reason"))

(defn httpkit-ws-handler
  "Return a map of HTTPkit-compatible handlers, describing how to start and manage an Electric server process, hooked onto a websocket."
  [ring-req boot-fn]
  (let [{:keys [on-open on-close on-ping #_on-pong #_on-error on-message]} (ering/electric-ws-handler ring-req boot-fn)]
    (-> {:init       (fn [_socket]) ; called pre handshake, no use case
         :on-open    on-open
         :on-close   (fn [socket status-code]
                       (ering/handle-close-status-code ring-req socket (or (STATUS-CODE status-code) status-code))
                       (on-close socket status-code))
         :on-ping    on-ping
         ;; :on-pong    on-pong  ; unsupported by HTTPKit
         ;; :on-error   on-error ; unsupported by HTTPKit
         :on-receive on-message}
        (update-vals
         (fn [f]
           (fn [async-channel & args]
             (apply f (HTTPKitSocket. async-channel) args)))))))

(defn wrap-electric-websocket-upgrade
  "An HTTPKit-compatible ring middleware, starting an Electric server program defined by `electric-boot-fn` on websocket connection.
  E.g.: ```
  (-> ring-handler
      (wrap-electric-websocket (fn [ring-req] (e/boot-server {} my-ns/MyElectricDefn ring-req)))
      (wrap-cookies)
      (wrap-params)
    )
  ```
  "
  [next-handler electric-boot-fn]
  (fn [ring-request]
    (if (ws/upgrade-request? ring-request)
      (httpkit/as-channel ring-request
                          (httpkit-ws-handler ring-request electric-boot-fn))
      (next-handler ring-request))))


;; END OF ORIGINAL HTTPKIT adapter hyperfiddle

(defn wrap-reject-stale-client
  "A Ring 1.11+ compatible middleware intercepting websocket UPGRADE request and
  checking if Electric client and Electric server versions matches.
  An Electric client is allowed to connect if:
  - its version matches the server's version,
  - the server does not have a defined version (dev mode).
  Otherwise, the websocket connection is gracefully rejected and the client is
  instructed to reload the page so to get new javascript assets.

  The rejection action can be redefined by providing an `on-mismatch` callback
  argument taking:
  - ring upgrade request,
  - client-version,
  - server-version,
  and returning the ring handler to be applied.

  e.g.
  With ring-jetty 1.11+
  ```
  (wrap-reject-stale-client handler {:hyperfiddle.electric/user-version nil})     ; will accept any client
  (wrap-reject-stale-client handler {:hyperfiddle.electric/user-version \"12345\"}) ; will only accept clients of version 12345
  ```

  With http-kit, which is not fully ring 1.11+ compliant as of Jan 9 2024
  ```
  (wrap-reject-stale-client handler {:hyperfiddle.electric/user-version \"12345\"}
    (fn on-mismatch [ring-request client-version server-version]
      (log/info 'wrap-reject-stale-client \": Electric client connection was rejected because client version doesn't match the server version. Client was instructed to perform a page reload so to get new javascript assets.\"
        {:client-version (pr-str client-version)
         :server-version (pr-str server-version)})
      (httpkit/as-channel ring-request ; this is HTTPkit specific
        (electric-httpkit/reject-websocket-handler 1008 \"stale client\") ; Websocket close code 1008 instructs the Electric client of the version mismatch
      )))
  ```"
  ([next-handler config]
   (wrap-reject-stale-client
    next-handler
    config
    (fn on-mismatch [_ring-request client-version server-version]
      (log/info
       'wrap-reject-stale-client
       ": Electric client connection was rejected because client version doesn't match the server version. Client was instructed to perform a page reload so to get new javascript assets."
       {:client-version (pr-str client-version)
        :server-version (pr-str server-version)})
      {::ws/listener (reject-websocket-handler 1008 "stale client")}))) ; https://www.rfc-editor.org/rfc/rfc6455#section-7.4.1
  ([next-handler {:keys [:hyperfiddle.electric/user-version]} on-missmatch]
   (fn [ring-request]
     (if (ws/upgrade-request? ring-request)
       (let [client-version (get-in ring-request [:query-params "ELECTRIC_USER_VERSION"])]
         (cond
           (nil? user-version)             (next-handler ring-request)
           (= client-version user-version) (next-handler ring-request)
           :else                           (on-missmatch ring-request client-version user-version)))
       (next-handler ring-request)))))


(defn wrap-electric-websocket
  "Open a websocket and boot an Electric server program defined by `entrypoint`.
  Takes:
  - a ring handler `next-handler` to call if the request is not a websocket upgrade (e.g. the next middleware in the chain),
  - a `config` map eventually containing {:hyperfiddle.electric/user-version <version>} to ensure client and server share the same version,
    - see `hyperfiddle.electric-ring-adapter/wrap-reject-stale-client`
  - an Electric `entrypoint`: a function (fn [ring-request] (e/boot-server {} my-ns/My-e-defn ring-request))
  "
  [next-handler config entrypoint]
  ;; Applied bottom-up
  (->
    (wrap-electric-websocket-upgrade next-handler entrypoint) ; 5. connect electric client
    ;;  (middleware/wrap-authenticated-request) ; 4. Optional - authenticate before opening a websocket
    ;;  (cookies/wrap-cookies) ; 3. makes cookies available to Electric app
    ;; 2. reject stale electric client
    (wrap-reject-stale-client
     config
     (fn on-mismatch [ring-request client-version server-version]
       (log/info
        'wrap-reject-stale-client
        ": Electric client connection was rejected because client version doesn't match the server version. Client was instructed to perform a page reload so to get new javascript assets."
        {:client-version (pr-str client-version)
         :server-version (pr-str server-version)})
       (httpkit/as-channel ring-request
                           (reject-websocket-handler 1008 "stale client"))))
    (wrap-params))) ; 1. parse query params

(defn get-modules
  [manifest-path]
  (when-let [manifest (io/resource manifest-path)]
    (let [manifest-folder (when-let [folder-name (second (rseq (str/split manifest-path #"\/")))]
                            (str "/" folder-name "/"))]
      (->> (slurp manifest)
           (edn/read-string)
           (reduce (fn [r module]
                     (assoc r
                            (keyword "hyperfiddle.client.module" (name (:name module)))
                            (str manifest-folder (:output-name module))))
                   {})))))

(defn index
  [bag]
  (html5
   [:html {:lang "en"}
    (str "<!-- {:hyperfiddle/user-version" (:hyperfiddle/user-version bag) " } -->")
    [:meta {:charset "utf-8"}]
    [:title "Hyperfiddle"]
    [:meta
     {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:script
     {:type "text/javascript" :src (str "/public" (:hyperfiddle.client.module/main bag))}] ;; llandmark: adjust the url
    [:link
     {:rel  "stylesheet"
      :href "/public/tailwind-styles.js"}]]))

(defn handle-index
  [{::keys [manifest-path] :as config} req]
  (if-let [bag (merge config (get-modules (check string? manifest-path)))]
    (-> (res/response (index bag)) ; TODO cache in prod mode
        (res/content-type "text/html") ; ensure `index.html` is not cached
        (res/header "Cache-Control" "no-store")
        #_(res/header "Last-Modified" (get-in response [:headers "Last-Modified"])) 
        ;; llandmark may be a problem that I had to remove last modified
    )
    (-> (res/not-found (pr-str ::missing-shadow-build-manifest)) ; can't inject js modules
        (res/content-type "text/plain"))))
