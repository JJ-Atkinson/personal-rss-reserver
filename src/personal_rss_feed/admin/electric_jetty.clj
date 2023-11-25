(ns personal-rss-feed.admin.electric-jetty
  "preferred entrypoint (cleaner middleware for integration) but no java 8 compat"
  (:require [clojure.java.io :as io]
            [hyperfiddle.electric-jetty-adapter :as adapter]
            [ring.adapter.jetty9 :as ring]
            [ring.util.response :as res]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:import [org.eclipse.jetty.server.handler.gzip GzipHandler]))

(defn template "Takes a `string` and a map of key-values `kvs`. Replace all instances of `$key$` by value in `string`"
  [string kvs]
  (reduce-kv (fn [r k v] (str/replace r (str "$" k "$") v)) string kvs))

(defn get-modules [manifest-path]
  (when-let [manifest (io/resource manifest-path)]
    (let [manifest-folder (when-let [folder-name (second (rseq (str/split manifest-path #"\/")))]
                            (str "/" folder-name "/"))]
      (->> (slurp manifest)
        (edn/read-string)
        (reduce (fn [r module] (assoc r (keyword "hyperfiddle.client.module" (name (:name module))) (str manifest-folder (:output-name module)))) {})))))

(defn index-page
  "Server the `index.html` file with injected javascript modules from `manifest.edn`. `manifest.edn` is generated by the client build and contains javascript modules information."
  [resources-path manifest-path]
  (fn [ring-req]
    (if-let [response (res/resource-response (str resources-path "/index.html"))]
      (if-let [modules (get-modules manifest-path)]
        (-> (res/response (template (slurp (:body response)) modules)) ; TODO cache in prod mode
          (res/content-type "text/html") ; ensure `index.html` is not cached
          (res/header "Cache-Control" "no-store")
          (res/header "Last-Modified" (get-in response [:headers "Last-Modified"])))
        ;; No manifest found, can't inject js modules
        (-> (res/not-found "Missing client program manifest")
          (res/content-type "text/plain")))
      ;; index.html file not found on classpath
      (-> (res/not-found "No index.html found")
        (res/content-type "text/plain")))))

(def ^:const VERSION (not-empty (System/getProperty "HYPERFIDDLE_ELECTRIC_SERVER_VERSION"))) ; see Dockerfile

(defn wrap-reject-stale-client
  "Intercept websocket UPGRADE request and check if client and server versions matches.
  An electric client is allowed to connect if its version matches the server's version, or if the server doesn't have a version set (dev mode).
  Otherwise, the client connection is rejected gracefully."
  [next-handler]
  (fn [ring-req]
    (if (ring/ws-upgrade-request? ring-req)
      (let [client-version (get-in ring-req [:query-params "HYPERFIDDLE_ELECTRIC_CLIENT_VERSION"])]
        (cond
          (nil? VERSION)             (next-handler ring-req)
          (= client-version VERSION) (next-handler ring-req)
          :else (adapter/reject-websocket-handler 1008 "stale client") ; https://www.rfc-editor.org/rfc/rfc6455#section-7.4.1
          ))
      (next-handler ring-req))))

(defn wrap-electric-websocket [next-handler]
  (fn [ring-request]
    (if (ring/ws-upgrade-request? ring-request)
      (let [electric-message-handler (partial adapter/electric-ws-message-handler ring-request)] ; takes the ring request as first arg - makes it available to electric program
        (ring/ws-upgrade-response (adapter/electric-ws-adapter electric-message-handler)))
      (next-handler ring-request))))

(defn electric-websocket-middleware [next-handler]
  (-> (wrap-electric-websocket next-handler) ; 4. connect electric client
    ;; 3. (cookies) is already enabled
    (wrap-reject-stale-client) ; 2. reject stale electric client
    ;; 1. (parse query params) is already enabled
    ))

(defn add-gzip-handler
  "Makes Jetty server compress responses. Optional but recommended."
  [server]
  (.setHandler server
    (doto (GzipHandler.)
      #_(.setIncludedMimeTypes (into-array ["text/css" "text/plain" "text/javascript" "application/javascript" "application/json" "image/svg+xml"])) ; only compress these
      (.setMinGzipSize 1024)
      (.setHandler (.getHandler server)))))
