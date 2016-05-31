(ns forces-assemble.config
  (:require [environ.core :refer [env]]))

(defn- missing-config-vars
  [required-keys]
  (reduce #(conj %1 (first %2)) [] (filter #(nil? (second %)) (map #(identity [% (env %)]) required-keys))))

(defn configuration-ok?
  [required-keys source-namespace]
  (let [missing (missing-config-vars required-keys)]
                         (if (empty? missing)
                           true
                           (throw (Exception. (str "Missing config variables: "
                                                   source-namespace
                                                   " requires the following config vars: "
                                                   missing))))))
