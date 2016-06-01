(ns forces-assemble.db
  (:require [forces-assemble.config :as config]
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
  (apply dissoc event (vec (clojure.set/difference (set (keys event)) #{:id
                                                                        :channel-id
                                                                        :author
                                                                        :title
                                                                        :body
                                                                        :data}))))

(defn- is-subscribed-to-channel
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
  (distinct (map get-user-token (get-channel-subscribers channel-id))))

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
