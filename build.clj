(ns build
  (:require [clojure.string :refer [split]]
            [clojure.tools.build.api :as b]
            [clojure.edn :refer [read-string]]))


(def version-file "version.edn")
(def build-dir "target")
(def jar-content (str build-dir "/classes"))

(def basis (b/create-basis {:project "deps.edn"}))
(def app "isntoolkit")
(defn uber-file [{:keys [isn-toolkit]}]
  (format "%s/%s-%s-standalone.jar" build-dir app isn-toolkit))

(defn clean [_]
  (b/delete {:path build-dir})
  (println "Cleaned build directory"))

(defn uber [_]
  (let [v (->> "version.edn" slurp read-string)
        uf (uber-file v)]
    (clean nil)
    (b/copy-dir {:src-dirs ["config"] :target-dir jar-content})
    (b/copy-dir {:src-dirs ["resources"] :target-dir jar-content})
    (b/compile-clj {:basis basis :src-dirs ["src"] :class-dir jar-content})
    (b/uber {:class-dir jar-content :uber-file uf :basis basis :main 'app.core})
    (println (format "Uber file created: \"%s\"" uf))))

(defn current [_] 
  (let [v (read-string( slurp "version.edn" )) ]
  (println "creating version : " v)))

(defn patch [_]
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
