(ns app.core-test
   (:require [clojure.test :refer :all]
                      [io.pedestal.test :refer :all]
                      [io.pedestal.http :as http]
                      [app.core :refer [cfg service-map] :as core]))

(def service (::http/service-fn (http/create-servlet service-map)))

;;;; Config tests
(deftest cfg-test  (is (= (:environment cfg) "dev")))

;;;; Simple page loading tests
(deftest home-test  (is (= (:status (response-for service :get "/")) 200)))
(deftest dashboard-test  (is (= (:status (response-for service :get "/dashboard")) 200)))

;;;; API tests
(deftest create-signals
  ;; Simple signal with no description (which we use for key value pairs beyond the indieweb event structure
  (is (= 201 (:status (response-for service
                                    :post "/micropub"
                                    :headers {"Authorization" "Bearer: XYZ" "Content-Type" "application/x-www-form-urlencoded"}
                                    :body "h=event&category=domain&name=ABCLab&summary=unsatisfactory"))))

  ;;  Complex example with key value pair description field
  (is (= 201 (:status (response-for service
                                    :post "/micropub"
                                    :headers {"Authorization" "Bearer: XYZ" "Content-Type" "application/x-www-form-urlencoded"}
                                    :body "h=event&category=domain&name=XYZLab&summary=unsatisfactory&description=cnCode=chickencode^countryCode=PL")))))
