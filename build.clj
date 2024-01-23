(ns build
  (:require [clojure.tools.build.api :as b]))

(def major-v 0)
(def minor-v 3)
(def version (format "%d.%d.%s" major-v minor-v (b/git-count-revs nil)))
(def version-file "version.edn")

(defn build
  "builds indieweb-toolkit - taking care of setup, version files etc"
  [_]
  (println "building...")
  (println "version: " version)
  (b/write-file {:path version-file :content {:isn-toolkit version}}))
