(ns personal-rss-feed.admin.electric-app.components.router
  "On hold, it wasn't important to my testing to have a different router"
  #?(:cljs (:require-macros personal-rss-feed.admin.electric-app.components.router))
  (:require
   [hyperfiddle.router :as hf.router]
   [contrib.sexpr-router :as sexpr]
   [hyperfiddle.history2 :as hf.history2]
   [hyperfiddle.electric :as e]))

(e/defn Router
  [history-component BodyFn]
  (binding [hf.history2/history history-component
            root-route]))

(defmacro router
  [history & body]
  `(new Router ~history (e/fn* [] ~@body)))

(comment
  (sexpr/decode (sexpr/encode '(("a" {:a 1 :b 2}) (b {:c :d})))))