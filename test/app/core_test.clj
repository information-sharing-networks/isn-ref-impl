(ns app.core-test
   (:require [clojure.test :refer :all]
             [io.pedestal.test :refer :all]
             [io.pedestal.http :as http]
             [aero.core :refer (read-config)]
             [app.core :refer [config service-map]]))

(def test-cfg (read-config "config.test.edn" {:profile :test-success}))
(defn service [] (::http/service-fn (http/create-servlet (service-map test-cfg))))

;; provide a version of our configuratin interceptor which uses a test configuration file
(alter-var-root #'app.core/cfg-tor (constantly {:name :cfg-tor :enter (fn [context] (assoc-in context [:request :cfg] (read-config "config.test.edn" {:profile :test-success})))}))

;;;; Config tests
(deftest cfg-test
  (let [{:keys [authcns environment] :as cfg} test-cfg]
    (is (= environment) "dev")
    (is (= (get-in authcns [])))))

;;;; Simple page loading tests
;; REVIEW: these are not very useful yet the service may be erroring we are just naively seeing if we get a 200
(deftest web-ui-tests
  (let [svc (service)]
    (is (= (:status (response-for svc :get "/")) 200))
    (is (= (:status (response-for svc :get "/dashboard")) 200))))

;;;; API tests
;;;; application/x-www-form-urlencoded content type
;;;; with success path for authcns (we are allowed to create signals)
(deftest create-signals
  (let [svc (service)]
    ;; Simple signal with no description (which we use for key value pairs beyond the indieweb event structure
    (is (= 201 (:status (response-for svc
                                      :post "/micropub"
                                      :headers {"Authorization" "Bearer: XYZ" "Content-Type" "application/x-www-form-urlencoded"}
                                      :body "h=event&category=pre-notification&category=isn@test-1.info-sharing.network&name=ABCLab&summary=unsatisfactory"))))
    
    ;;  Complex example with key value pair description field
    (is (= 201 (:status (response-for svc
                                      :post "/micropub"
                                      :headers {"Authorization" "Bearer: XYZ" "Content-Type" "application/x-www-form-urlencoded"}
                                      :body "h=event&category=pre-notification&category=isn@test-1.info-sharing.network&name=XYZLab&summary=unsatisfactory&description=cnCode=chickencode^countryOfOrigin=PL"))))

    ;;  An incorrect signal and domain category pairing should yield a 400
    (is (= 400 (:status (response-for svc
                                      :post "/micropub"
                                      :headers {"Authorization" "Bearer: XYZ" "Content-Type" "application/x-www-form-urlencoded"}
                                      :body "h=event&category=pre-notification&category=isn@sample-1.info-sharing.network&name=XYZLab&summary=unsatisfactory&description=cnCode=chickencode^countryOfOrigin=PL"))))))

;;;; API tests - with success path (we are allowed to read signals)
; REVIEW: this is of limited use - should also test for properties of body as we have created signals before this
(deftest read-signals-success
  (let [svc (service)
        {:keys [body status] :as rsp} (response-for svc :get "/signals" :headers {"Authorization" "Bearer: XYZ"})]
    (is (= 200 status))))

;; provide a version of config in app.core that uses a test configuration
                                        ;(alter-var-root #'app.core/config (read-config "config.test.edn" {:profile :test-failure}))



;;;; API tests - with failure path (we are not allowed to read the signals due to no authcns access in new config
;; REVIEW: will need to work with failure path here possibly via alter-var-root or other mechanism
;; (deftest read-signals-fail
;;   ;; provide a version of our configuratin interceptor which uses a test configuration file
;;   (alter-var-root #'app.core/cfg-tor (fn [_] {:name :cfg-tor :enter (fn [context] (assoc-in context [:request :cfg] (read-config "config.test.edn" {:profile :test-failure})))}))
;;   (let [svc (service)
;;         {:keys [body status] :as rsp} (response-for svc
;;                                                     :get "/signals"
;;                                                     :headers {"Authorization" "Bearer: XYZ"})]
;;     (is (= 200 status))
;;                                         ;(is ())
;;     (println "rsp body : " body)
;;     ))
