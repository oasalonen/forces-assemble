(ns forces-assemble.config
  (:require [environ.core :refer [env]]))

(defn- missing-config-vars
  [required-keys]
  (reduce #(conj %1 (first %2)) [] (filter #(nil? (second %)) (map #(identity [% (env %)]) required-keys))))

(defn configuration-ok?
  [required-keys]
  (let [missing (missing-config-vars required-keys)]
                         (if (empty? missing)
                           true
                           (throw (Exception. (str "Following required variables have not been defined: "
                                                   missing))))))
