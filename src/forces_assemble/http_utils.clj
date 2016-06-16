(ns forces-assemble.http-utils
  (:require [cheshire.core :as che]
            [clojure.java.io :as io]
            [clojure.string :as cstr]))

(defn noop [context] {})

(defn build-entry-url
  ([context id] (context nil id))
  ([context path id]
   (let [request (:request context)] 
     (java.net.URL. (format "%s://%s:%s%s/%s"
                            (name (:scheme request))
                            (:server-name request)
                            (:server-port request)
                            (or path (:uri request))
                            (str id))))))

(defn build-entry-relative-url
  ([context id] (context nil id))
  ([context path id]
   (.getPath (build-entry-url context path id))))

(defn check-content-type [context content-types]
  (if (#{:put :post} (get-in context [:request :request-method]))
    (let [content-type (cstr/trim (first (cstr/split (get-in context [:request :headers "content-type"]) #";")))]
      (or
       (some #{content-type} content-types)
       [false {:message "Unsupported Content-Type"}]))
    true))

(defn- is-put-post-patch [context]
  (if (#{:put :post :patch} (get-in context [:request :request-method]))
    true))

(defn parse-json-body [body]
  (condp instance? body
    java.lang.String (che/parse-string body true)
    (che/parse-stream (io/reader body) true)))

(defn parse-json
  [context key]
  (when (is-put-post-patch context)
    (try
      (let [json ((comp parse-json-body get-in) context [:request :body])]
        [false {key json}])
      (catch Exception e
        (.printStackTrace e)
        {:message (str "Exception: " (.getMessage e))}))))

(defn get-authorization-token [context]
  (get-in context [:request :headers "authorization"]))
