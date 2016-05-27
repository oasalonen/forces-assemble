(ns forces-assemble.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.adapter.jetty :as jetty]))

(defroutes app-routes
  (GET "/" [] "Hello World1")
  (route/not-found "Not Found"))

(def app
  (wrap-defaults app-routes site-defaults))

(defn def-server []
  (defonce server
    (jetty/run-jetty #'app {:port 8000 :join? false})))
