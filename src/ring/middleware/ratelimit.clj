(ns ring.middleware.ratelimit
  (:use [ring.middleware.ratelimit util backend local-atom limits])
  (:import [java.text SimpleDateFormat]
           [java.util Date TimeZone Locale]))

(defn ip-limit [n] (-> n limit wrap-limit-ip))

(defn user-limit [n] (-> n limit wrap-limit-user))

(defn role-limit [role n] (-> n limit (wrap-limit-role role)))

(defn- ^SimpleDateFormat formatter [format]
  (doto (SimpleDateFormat. ^String format Locale/US)
    (.setTimeZone (TimeZone/getTimeZone "GMT"))))

(defn default-config []
  {:limits [(ip-limit 100)]
   :backend (local-atom-backend)
   :err-handler (fn [req]
                  {:status 429
                   :headers {"Content-Type" "application/json"
                             "Retry-After" (let [d (Date.)]
                                             (.setHours d (inc (.getHours d)))
                                             (.setMinutes d 0)
                                             (.setSeconds d 0)
                                             (.format (formatter "EEE, dd MMM yyyy HH:mm:ss zzz") d))}
                   :body "{\"error\": \"Too Many Requests\"}"})})

(defn wrap-ratelimit
  ([handler] (wrap-ratelimit handler {}))
  ([handler config]
   (let [config* (merge (default-config) config)
         limits (:limits config*)
         backend (:backend config*)
         err-handler (:err-handler config*)]
     (fn [req]
       (if (available? backend)
         (do
           (when (not= (get-hour backend) (current-hour))
             (reset-limits! backend (current-hour)))
           (if-let [limiter (first (filter #((:filter %) req) limits))]
             (let [limit (:limit limiter)
                   thekey (str (:key-prefix limiter) ((:getter limiter) req))
                   current (get-limit backend limit thekey)
                   remaining (- limit current)
                   is-over (< remaining 0)
                   rl-headers {"X-RateLimit-Limit" (str limit)
                               "X-RateLimit-Remaining" (if is-over "0" (str remaining))}
                   h (if is-over err-handler handler)
                   rsp (h req)]
               (if (map? rsp)
                 (assoc rsp :headers (merge (:headers rsp) rl-headers))
                 rsp))
             (handler req)))
         (handler req))))))
