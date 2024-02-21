(ns app.core-test
   (:require [clojure.test :refer :all]
             [io.pedestal.test :refer :all]
             [io.pedestal.http :as http]
             [app.core :refer [config service-map] :as core]))

(def service (::http/service-fn (http/create-servlet (service-map config))))

;;;; Config tests
(deftest cfg-test  (is (= (:environment config) "dev")))

;;;; Simple page loading tests
(deftest home-test  (is (= (:status (response-for service :get "/")) 200)))
(deftest dashboard-test  (is (= (:status (response-for service :get "/dashboard")) 200)))

;;;; API tests
(deftest create-signals
  ;; Simple signal with no description (which we use for key value pairs beyond the indieweb event structure
  (is (= 201 (:status (response-for service
                                    :post "/micropub"
                                    :headers {"Authorization" "Bearer: XYZ" "Content-Type" "application/x-www-form-urlencoded"}
                                    :body "h=event&category=pre-notification&category=isn@sample-isn-1.my-example.xyz&name=ABCLab&summary=unsatisfactory"))))

  ;;  Complex example with key value pair description field
  (is (= 201 (:status (response-for service
                                    :post "/micropub"
                                    :headers {"Authorization" "Bearer: XYZ" "Content-Type" "application/x-www-form-urlencoded"}
                                    :body "h=event&category=pre-notification&category=isn@sample-isn-1.my-example.xyz&name=XYZLab&summary=unsatisfactory&description=cnCode=chickencode^countryCode=PL")))))
