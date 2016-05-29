(ns forces-assemble.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults secure-api-defaults site-defaults]]
            [ring.adapter.jetty :as jetty]
            [environ.core :refer [env]]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.operators :refer :all]
            [clj-http.client :as http]
            [forces-assemble.http-utils :refer :all]
            [liberator.core :refer [defresource]]
            [liberator.dev :refer [wrap-trace]]))

;; Mongo
(def mongo-uri
  (or (env :mongodb-mongolab-uri)
      "mongodb://localhost/test"))
(def mongo-connection-result (mg/connect-via-uri mongo-uri))
(def mongodb (:db mongo-connection-result))

(def coll-users "users")
(def coll-channels "channels")
(def coll-events "events")

(defn is-subscribed-to-channel
  [channel-id user-id]
  (let [cursor (mc/find mongodb
                        coll-channels
                        {:_id channel-id :subscribers user-id})]
    (.hasNext (.iterator cursor))))

(defn subscribe-to-channel
  [channel-id user-id]
  (if-let [not-subscribed? (not (is-subscribed-to-channel channel-id user-id))]
    (do
      (mc/update mongodb
                 coll-channels
                 {:_id channel-id}
                 {$push {:subscribers user-id}}
                 {:upsert true})
      (mc/update mongodb
                 coll-users
                 {:_id user-id}
                 {$push {:channels channel-id}}
                 {:upsert true}))))


(defn refresh-user-token
  [user-id token]
  (mc/update mongodb
             coll-users
             {:_id user-id}
             {:token token}
             {:upsert true}))

(defn get-subscribed-channels
  [user-id]
  (:channels (mc/find-map-by-id mongodb
                                coll-users
                                user-id)))

(defn get-channel-subscribers
  [channel-id]
  (:subscribers (mc/find-map-by-id mongodb
                                   coll-channels
                                   channel-id)))

(defn get-user-token
  [user-id]
  (:token (mc/find-map-by-id mongodb
                             coll-users
                             user-id)))

(defn get-user-tokens-on-channel
  [channel-id]
  (map get-user-token (get-channel-subscribers channel-id)))

(defn add-event-to-channel-db
  [channel-id event]
  (mc/insert-and-return mongodb
                        coll-events
                        (assoc event :channel-id channel-id)))

;; HTTP
(def firebase-send-uri "https://fcm.googleapis.com/fcm/send")
(def application-json "application/json")

(defn build-notification
  [event client]
  {:to client
   :priority "high"
   :notification {:title (or (:title event) "")
                  :body (or (:body event) "")
                  :sound "default"}})

(def client-key "dHfG35KW8yA:APA91bGFFLRyvqzK6mUYK8DBQloGit9Uq3SZ0VeLq0lP80cCiPYtk1huM1Ls12zbU8nJK9Ag0NJS-3FEJ3pkbX0gMHzHvnbvEXyvIUUkg4aLYBE4rwSuJZiZC6_M-25Ozw119C2N7UE0")

(defn debug-send-push-notification [client event]
  (println (str "to: " client "\n" event)))

(defn send-push-notification
  [client event]
  (println (str "Pushing: " client))
  (println (str "Message: " (pr-str event)))
  (http/post firebase-send-uri
             {:content-type :json
              :headers {"Authorization" (str "key=" (or (env :firebase-api-key) ""))}
              :form-params (build-notification event client-key)}))

;; Event logic
(defn add-event-to-channel
  [channel-id event]
  (let [added-event (add-event-to-channel-db channel-id event)]
    (map #(send-push-notification % added-event) (get-user-tokens-on-channel channel-id))))

;; Server

(defresource user-tokens [user-id]
  :allowed-methods [:put]
  :available-media-types [application-json]
  :known-content-type? #(check-content-type % [application-json])
  :malformed? #(parse-json % ::data)
  :put! (fn [context]
          (refresh-user-token user-id (:token (::data context)))))

(defresource channel-events [channel-id]
  :allowed-methods [:post]
  :available-media-types [application-json]
  :known-content-type? #(check-content-type % [application-json])
  :malformed? #(parse-json % ::data)
  :post! (fn [context]
           (add-event-to-channel channel-id (::data context))))

(defresource channel-subscribers [channel-id]
  :allowed-methods [:post]
  :available-media-types [application-json]
  :known-content-type? #(check-content-type % [application-json])
  :malformed? #(parse-json % ::data)
  :post! (fn [context]
           (subscribe-to-channel channel-id (:username (::data context)))))

(defroutes assemble-routes
  (GET "/" [] "Hello World2")
  (ANY "/users/:id/token" [id] (user-tokens id))
  (ANY "/channels/:id/events" [id] (channel-events id))
  (ANY "/channels/:id/subscribers" [id] (channel-subscribers id))
  (route/not-found "Not Found"))

(def dev-app
  (wrap-trace (wrap-defaults assemble-routes (secure-api-defaults :proxy true)) :header :ui))

(def app
  (wrap-defaults assemble-routes (secure-api-defaults :proxy true)))

(defn def-server []
  (def server
    (jetty/run-jetty #'dev-app
                     {:port 8000
                      :join? false
                      :ssl? true
                      :ssl-port 8443
                      :keystore (str (env :home) "/jetty.keystore")
                      :key-password (env :jetty-keystore-password)})))

(defn restart-server []
  (.stop server)
  (def-server)
  (.start server))
