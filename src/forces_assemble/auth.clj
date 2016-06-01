(ns forces-assemble.auth
  (:require [forces-assemble.config :as config]
            [clojure.java.io :as io]
            [environ.core :refer [env]]
            [cheshire.core :as che])
  (:import (com.google.firebase FirebaseApp FirebaseOptions FirebaseOptions$Builder)
           (com.google.firebase.auth FirebaseAuth)
           (com.google.firebase.tasks OnSuccessListener OnFailureListener)))

(def config-keys [:firebase-auth-private-key-id
                  :firebase-auth-private-key
                  :firebase-auth-client-email
                  :firebase-auth-client-id])
(def configuration-ok? (config/configuration-ok? config-keys *ns*))
(def auth-config-path "auth-config.json")
(def auth-config-key-prefix "firebase-auth-")

(defn- get-var-as-map
  [key]
  {(.replace (.replace (name key) auth-config-key-prefix "") "-" "_") (env key)})

(defn- get-auth-secrets
  []
  (reduce merge (map get-var-as-map config-keys)))

(defn- auth-config []
  (let [partial-auth-config (che/parse-stream (io/reader (io/resource auth-config-path)))]
    (merge partial-auth-config (get-auth-secrets))))

(defn- create-firebase-options []
  (.build (doto (FirebaseOptions$Builder.)
            (.setServiceAccount (->> (auth-config)
                                     (che/generate-string)
                                     (.getBytes)
                                     (io/input-stream))))))

(defonce fbapp (FirebaseApp/initializeApp (create-firebase-options)))

(defn- get-authenticated-user
  [token-string fbtoken]
  {:token token-string
   :user-id (.getUid fbtoken)
   :name (.getName fbtoken)})

(defn authenticate-token
  [token]
  (if-not token
    (throw (Exception. "Authorization header missing or no authorization token provided")))
  (let [p (promise)]
    (future (doto (.verifyIdToken (FirebaseAuth/getInstance fbapp) token)
              (.addOnFailureListener (reify OnFailureListener
                                       (onFailure [_ exception]
                                         (deliver p exception))))
              (.addOnSuccessListener (reify OnSuccessListener
                                       (onSuccess [_ result]
                                         (deliver p result))))))
    (let [result (deref p 10000 :timeout)]
      (if (= :timeout result)
        (throw (Exception. "Authentication service timeout. Please try again later."))
        (if (instance? Exception result)
          (throw result)
          (get-authenticated-user token result))))))

