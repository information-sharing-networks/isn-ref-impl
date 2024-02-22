(ns build
  (:require [clojure.string :refer [split]]
            [clojure.tools.build.api :as b]
            [clojure.edn :refer [read-string]]))

(def version-file "version.edn")

(defn change [_]
  (println "bumping version patch")
  (let [v (->> "version.edn" slurp read-string)
        [major minor patch] (split (:isn-toolkit v) #"\.")
        new-v (str major "." minor "." (inc (Integer/parseInt patch)))]
    (println "creating version : " new-v)
    (b/write-file {:path version-file :content {:isn-toolkit new-v}})))

(defn minor [_]
  (println "bumping version minor")
  (let [v (->> "version.edn" slurp read-string)
        [major minor patch] (split (:isn-toolkit v) #"\.")
        new-v (str major "." (inc (Integer/parseInt minor)) "." 0)]
    (println "creating version : " new-v)
    (b/write-file {:path version-file :content {:isn-toolkit new-v}})))
