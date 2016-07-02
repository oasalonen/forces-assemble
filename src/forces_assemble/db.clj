(ns forces-assemble.db
  (:require [clojure.set]
            [clojure.string :as cstr]
            [forces-assemble.config :as config]
            [environ.core :refer [env]]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.util :as mu]
            [monger.operators :refer :all]))

(def mongo-config-keys [:mongodb-mongolab-uri])
(def mongo-configuration-ok? (config/configuration-ok? mongo-config-keys *ns*))

(def mongo-connection-result (mg/connect-via-uri (env :mongodb-mongolab-uri)))
(def mongodb (:db mongo-connection-result))

(def coll-users "users")
(def coll-channels "channels")
(def coll-events "events")

(defn- import-data
  [data]
  (if-let [id (:id data)]
    (assoc (dissoc data :id) :_id id)))

(defn- export-data
  [data]
  (dissoc (assoc data :id (str (:_id data))) :_id))

(defn- import-event
  [event]
  (select-keys event [:id
                      :channel-id
                      :author
                      :title
                      :body
                      :data
                      :participant]))

(defn- is-subscribed-to-channel
  [channel-id user-id]
  (let [cursor (mc/find mongodb
                        coll-channels
                        {:_id channel-id :subscribers user-id})]
    (.hasNext (.iterator cursor))))

(defn subscribe-to-channel
  [channel-id user-id]
  (when (not (is-subscribed-to-channel channel-id user-id))
    (do
      (mc/update-by-id mongodb
                       coll-channels
                       channel-id
                       {$push {:subscribers user-id}}
                       {:upsert true})
      (mc/update-by-id mongodb
                       coll-users
                       user-id
                       {$push {:channels channel-id}}
                       {:upsert true}))))

(defn unsubscribe-from-channel
  [channel-id user-id]
  (mc/update-by-id mongodb
                   coll-channels
                   channel-id
                   {$pull {:subscribers user-id}})
  (mc/update-by-id mongodb
                   coll-users
                   user-id
                   {$pull {:channels channel-id}}))

(defn refresh-user-notification-token
  [user-id token]
  (mc/update mongodb
             coll-users
             {:_id user-id}
             {$set {:notification-token token}}
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

(defn get-user-notification-token
  [user-id]
  (:notification-token (mc/find-map-by-id mongodb
                                          coll-users
                                          user-id)))

(defn get-user-notification-tokens-on-channel
  [channel-id]
  (filter cstr/blank? (distinct (map get-user-notification-token (get-channel-subscribers channel-id)))))

(defn add-event-to-channel
  [channel-id event]
  (let [event-id (mu/object-id)]
    (export-data (mc/insert-and-return mongodb
                                       coll-events
                                       (assoc (import-event event) :_id event-id :channel-id channel-id)))))

(defn get-event
  [event-id]
  (export-data (mc/find-map-by-id mongodb
                                  coll-events
                                  (mu/object-id event-id))))

(defn get-event-participants
  [event-id]
  (map #(select-keys % [:user-id :name]) (:participants (get-event event-id))))

(defn add-event-participant
  [event-id user]
  (when (not-any? #(= (:user-id %) (:user-id user)) (get-event-participants event-id))
    (mc/update mongodb
               coll-events
               {:_id (mu/object-id event-id)}
               {$push {:participants (select-keys user [:user-id :name])}})))
