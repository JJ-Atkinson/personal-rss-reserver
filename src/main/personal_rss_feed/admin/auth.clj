(ns personal-rss-feed.admin.auth
  (:require [buddy.sign.jwt :as jwt]
            [buddy.hashers :as hashers]
            [buddy.sign.util :as sign.util]
            [datalevin.core :as d]
            [integrant.core :as ig]
            [taoensso.encore :as enc]
            [taoensso.timbre :as log]))

(defonce !config (atom nil))

(defn generate-password-crypt
  [{::keys [hash-options]} cleartext]
  (hashers/derive cleartext hash-options))

(defn generate-jwt
  [{::keys [jwt-options] :as config} claims]
  (let [claims (assoc claims
                      :exp
                      (+ (sign.util/now) (:exp-period-s jwt-options)))]
    (jwt/sign claims (:secret jwt-options) (:buddy jwt-options))))

(defn jwt-claims
  [{::keys [jwt-options] :as config} message]
  (try (let [claims
             (jwt/unsign message (:secret jwt-options) (:buddy jwt-options))]
         (when-not (:exp claims)
           (throw (ex-info "The EXP claim is missing and could not be verified!"
                           {:claims claims})))
         claims)
       (catch Exception e (log/info "Bad or missing token!" e message) nil)))

(defn generate-jwt-from-credentials
  [{:keys [db/conn] :as config} username password-cleartext]
  (enc/when-let [user            (d/entity (d/db conn) [:user/uname username])
                 {:keys [valid]} (hashers/verify password-cleartext
                                                 (:user/password-crypt user))]
    (if valid
      (generate-jwt config (select-keys user [:user/uname :user/admin?]))
      (throw (ex-info "Credentials invalid!"
                      {:username username :password password-cleartext})))))

(defmethod ig/init-key ::auth [_ config] (reset! !config config) config)

(defmethod ig/suspend-key! ::auth [_ _])

(defmethod ig/resume-key ::auth [_ _ _ _])

(defmethod ig/halt-key! ::auth [_ conn] nil)

(comment
  (def message (generate-jwt @!config {:a 1 :b 2 :c 3}))
  (jwt-claims @!config message)
  (generate-password-crypt @!config "hello-world")
  (generate-jwt-from-credentials @!config "jarrett" "password"))
