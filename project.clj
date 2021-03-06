(defproject forces-assemble "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [compojure "1.4.0"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [ring/ring-defaults "0.1.5"]
                 [ring-logger "0.7.6"]
                 [com.novemberain/monger "3.0.2"]
                 [com.novemberain/langohr "3.6.1"]
                 [clj-http "3.1.0"]
                 [environ "1.0.3"]
                 [cheshire "5.6.1"]
                 [liberator "0.14.1"]
                 [com.google.firebase/firebase-server-sdk "3.0.0"]
                 [clojure.java-time "0.2.1"]
                 [danlentz/clj-uuid "0.1.6"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/core.async "0.2.385"]]
  :plugins [[lein-ring "0.9.7"]
            [lein-environ "1.0.3"]]
  :ring {:handler forces-assemble.handler/dev-app}
  :profiles
  {:dev [{:dependencies [[javax.servlet/servlet-api "2.5"]
                          [ring/ring-mock "0.3.0"]]}
         :dev-config]})
