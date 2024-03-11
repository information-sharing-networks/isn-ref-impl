(ns app.core-test
   (:require [clojure.test :refer :all]
             [io.pedestal.test :refer :all]
             [io.pedestal.http :as http]
             [aero.core :refer (read-config)]
             [app.core :refer [service-map] :as core]))

(defn config
  ([] (config {}))
  ([{:keys [profile] :or {profile :dev-success} :as prof-map}] (read-config "config.test.edn" prof-map)))
  
(defn service [cfg] (::http/service-fn (http/create-servlet (service-map cfg))))

;;;; Config tests
(deftest cfg-test
  (let [cfg (config)]
    (is (= (:environment cfg)) "dev")))

;;;; Simple page loading tests
(deftest web-ui-tests
  (let [cfg (config)
        svc (service cfg)]
    (is (= (:status (response-for svc :get "/")) 200))
    (is (= (:status (response-for svc :get "/dashboard")) 200))))

;;;; API tests - with success path for authcns (we are allowed to create signals)
(deftest create-signals
  (let [cfg (config)
        svc (service cfg)]
    ;; Simple signal with no description (which we use for key value pairs beyond the indieweb event structure
    (is (= 201 (:status (response-for svc
                                      :post "/micropub"
                                      :headers {"Authorization" "Bearer: XYZ" "Content-Type" "application/x-www-form-urlencoded"}
                                      :body "h=event&category=pre-notification&category=isn@btd-1.info-sharing.network&name=ABCLab&summary=unsatisfactory"))))
    
    ;;  Complex example with key value pair description field
    (is (= 201 (:status (response-for svc
                                      :post "/micropub"
                                      :headers {"Authorization" "Bearer: XYZ" "Content-Type" "application/x-www-form-urlencoded"}
                                      :body "h=event&category=pre-notification&category=isn@btd-1.info-sharing.network&name=XYZLab&summary=unsatisfactory&description=cnCode=chickencode^countryOfOrigin=PL"))))

    ;;  An incorrect signal and domain category pairing should yield a 400
    (is (= 400 (:status (response-for svc
                                      :post "/micropub"
                                      :headers {"Authorization" "Bearer: XYZ" "Content-Type" "application/x-www-form-urlencoded"}
                                      :body "h=event&category=pre-notification&category=isn@sample-1.info-sharing.network&name=XYZLab&summary=unsatisfactory&description=cnCode=chickencode^countryOfOrigin=PL"))))))

;;;; API tests - with failure path (we are not allowed to read the signals due to no authcns access in new config
