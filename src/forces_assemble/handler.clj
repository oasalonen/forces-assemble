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

;; HTTP
(def firebase-send-uri "https://fcm.googleapis.com/fcm/send")

(defn build-notification
  [title body client]
  {:to client
   :priority "high"
   :notification {:title title
                  :body body
                  :sound "default"}})

(def client-key "cpNE0uW_5sk:APA91bHmkiSpbq1s2zOKs_2hctUn7N_o_80Jt3diomlq5xClfk3XWImAkG5drS2pTA0oSLOnn4b14bUybiIL1e2uX_r6XOYm-dzIt86Y9qUw_rCowE9DfKMMyqHQa906_xNu2xInjbjh")

(defn send-push-notification
  [client]
  (http/post firebase-send-uri
             {:content-type :json
              :headers {"Authorization" (str "key=" (or (env :firebase-api-key) ""))}
              :form-params (build-notification "Hi" "You are notified" client)}))

;; Server

(defresource user-tokens [user-id]
  :allowed-methods [:put]
  :available-media-types ["application/json"]
  :known-content-type? #(check-content-type % ["application/json"])
  :malformed? #(parse-json % ::data)
  :put! (fn [context]
          (refresh-user-token user-id (:token (::data context)))))

(defroutes app-routes
  (GET "/" [] "Hello World2")
  (ANY "/users/:id/token" [id] (user-tokens id))
  (route/not-found "Not Found"))

(def app
  (wrap-defaults app-routes (secure-api-defaults :proxy true)))

(def handler
  (-> app-routes
      (wrap-trace :header :ui)))

(defn def-server []
  (def server
    (jetty/run-jetty #'app
                     {:port 8000
                      :join? false
                      :ssl? true
                      :ssl-port 8443
                      :keystore (str (env :home) "/jetty.keystore")
                      :key-password (env :jetty-keystore-password)})))
