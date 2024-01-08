(ns app.core
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :refer [file]]
            [clojure.pprint :as pp]
            [clojure.set :refer [intersection]]
            [clojure.spec.alpha :as s]
            [clojure.string :refer [blank? includes? lower-case replace split trim]]
            [clojure.walk :refer [keywordize-keys]]
            [aero.core :refer (read-config)]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :refer [body-params]]
            [io.pedestal.http.ring-middlewares :as mw]
            [io.pedestal.log :refer [debug info error]]
            [java-time.api :as jt]
            [medley.core :refer [distinct-by]]
            [ring.middleware.session.cookie :as cookie]
            [ring.util.response :refer [redirect response]]
            [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [lambdaisland.uri :refer [uri]]
            [hiccup.page :refer [html5]]
            [net.cgrand.enlive-html :refer [attr? attr-values html-resource select text]]
            [voxmachina.itstore.postrepo :as its]
            [voxmachina.itstore.postrepo-fs :as itsfile])
  (:import java.io.StringReader
           java.util.UUID))

;;;; Config and constants
;;;; ===========================================================================

(alter-var-root #'org.httpkit.client/*default-client* (fn [_] sni-client/default-client))

(def dt-fmt "dd-MM-yyyy HH:mm:ss")

(def cfg (read-config "config.edn" {}))

(def data-path (:data-path cfg))

(def sig-path (str data-path "/signals"))

(defn dev? [] (= (:environment cfg) "dev"))

(def site-root (:site-root cfg))

(def site-type (:site-type cfg))

(def pr-fs (itsfile/repo {:data-path data-path}))

(def form-enc {"Accept" "application/x-www-form-urlencoded"})

;;;; Utility functions.
;;;; ===========================================================================

(defn s-uri? [x]  (if (uri? (java.net.URI. x)) true false))
(defn s-exists? [x] (if (.exists (file x)) true false))

(s/def ::site-name  string?)
(s/def ::site-root string?)
(s/def ::authcn-ids (s/coll-of string? :kind set? :min-count 1 :distinct true))
(s/def ::data-path s-exists?)
(s/def ::dev-site s-uri?)
(s/def ::config (s/keys :req-un [::site-name ::site-root ::authcn-ids ::data-path ::dev-site]))

(defn- status [req] {:status 200 :body "Service is running"})

(def htm-tors [(body-params) http/html-body])

(def api-tors [(body-params)])

(def ses-tors [(mw/session {:store (cookie/cookie-store)}) mw/params mw/keyword-params (body-params) http/html-body])

(defn rel-root [] (:rel-root cfg)) ;; REVIEW: use function to provision for multi-tenancy

(defn client-id [] (str (rel-root) "/"))

(defn redirect-uri [] (str (client-id) "indieauth-redirect"))

(defn login-uri [] (:indielogin-uri cfg))

(defn- validate-token [token]
  (let [rsp @(client/get "https://tokens.indieauth.com/token" {:headers {"Authorization" token "Accept" "application/json"}})]
    (if (dev?)
      {"me" (:dev-site cfg)}
      (json/read-str (:body rsp)))))

(defn- authcn? [id]
  (let [host (trim (:host (uri id)))
        ids (:authcn-ids cfg)]
    (and (not-empty ids) host (some #{host} ids))))

(defn- arr-syntax-key [m] (first (for [[k v] m :when (.contains (name k) "category")] v)))

(defn enlive->hiccup
  [el]
  (if-not (string? el)
    (->> (map enlive->hiccup (:content el))
         (concat [(:tag el) (:attrs el)])
         (keep identity)
         vec)
    el))

(defn html->enlive [html] (first (html-resource (StringReader. html))))

(defn html->hiccup [html] (-> html html->enlive enlive->hiccup))

(defn file->edn [file] (->> file slurp edn/read-string))

(defn make-filter [[k v]] (filter #(re-find (re-pattern v) (k %))))

(defn- sorted-instant-edn [{:keys [path api? filters] :or {path sig-path api? true filters {}}}]
  (let [xs-files   (filter #(.isFile %) (file-seq (file path)))
        xs-edn (map file->edn (map str xs-files))
        current-xs (remove #(jt/before? (jt/instant (:end %)) (jt/instant)) xs-edn)
        fs-xs (try (sequence (reduce comp (map make-filter filters)) current-xs) (catch Exception e  current-xs))
        sigs (if api? (map #(dissoc % :permafrag :summary) fs-xs) fs-xs)]
    (group-by :correlation-id sigs)))

(defn- str->inst [x]
  (if (includes? x "T")
    (str (jt/instant x))
    (str (jt/instant (jt/zoned-date-time (jt/local-date-time dt-fmt x) "UTC")))))

(defn- participants-edn [{:keys [path api? filters] :or {path sig-path api? false filters {}}}]
  (let [xs-files (filter #(.isFile %) (file-seq (file path)))
        xs-edn (map file->edn (map str xs-files))
        xs-isn (filter #(= (:category %) "isn-participant") xs-edn)
        xs (distinct-by #(% :provider) xs-isn)]
    (group-by :correlation-id xs)))

(defn- mirrors-edn [{:keys [path api? filters] :or {path sig-path api? false filters {}}}]
  (let [xs-files (filter #(.isFile %) (file-seq (file path)))
        xs-edn (map file->edn (map str xs-files))
        xs-isn (filter #(= (:category %) "isn-mirror") xs-edn)
        xs (distinct-by #(% :name) xs-isn)]
    (group-by :correlation-id xs)))

(defn- selector [src path] (first (map text (select src path))))


;;;; Components
;;;; ===========================================================================

(defn head []
  [:html [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
          [:link {:rel "authorization_endpoint" :href "https://indieauth.com/auth"}]
          [:link {:rel "token_endpoint" :href "https://tokens.indieauth.com/token"}]
          [:link {:rel "micropub" :href (str rel-root "/micropub")}]
          [:link {:rel "webmention" :href (str (:rel-root cfg) "/webmention")}]
          [:link {:rel "microsub" :href (:microsub-uri cfg)}]
          [:link {:rel "stylesheet" :type "text/css" :href "/css/bootstrap.min.css"}]
          [:link {:rel "stylesheet" :type "text/css" :href "/css/bootstrap-icons.css"}]
          [:link {:rel "stylesheet" :type "text/css" :href "/css/style.css"}]
          [:title (:site-name cfg)]]])

(defn navbar [{:keys [token user]}]
  [:nav {:class "navbar navbar-expand-md navbar-light fixed-top bg-light"}
   [:div {:class "container-fluid"}
    [:a {:class "navbar-brand" :href "/"} (:site-name cfg)]
    [:button {:class "navbar-toggler" :type "button" :data-toggle "collapse" :data-target "#navbarCollapse" :aria-controls "navbarCollapse" :aria-expanded "false" :aria-label "Toggle navigation"}
     [:span {:class "navbar-toggler-icon"}]]
    [:div {:class "collapse navbar-collapse" :id "navbarCollapse"}
     [:ul {:id "header-page-links" :class "navbar-nav mr-auto"}
      [:li {:class "nav-item"} [:a {:class "nav-link" :href "/dashboard"} "Dashboard"]]
      [:li {:class "nav-item"} [:a {:class "nav-link" :href "/about"} "About"]]
      [:li {:class "nav-item"} [:a {:class "nav-link" :href "/documentation"} "Documentation"]]
      (when user [:li {:class "nav-item"} [:a {:class "nav-link" :href "/account"} "Account"]])]]]])

(defn info-mirror []
  [:div {:class "col-lg-9" :role "main"}
   [:div {:class "alert alert-info mirror-info" :role "alert"}
    [:p "This is an Ecosystem of Trust demonstrator ISN mirror"]
    [:p "Topics are published here that are relevant to the ISN participants who subscribe to this mirror"]]])

(defn info-isn []
  [:div {:class "col-lg-9" :role "main"}
   [:div {:class "alert alert-success mirror-info" :role "alert"}
    [:p "This is an Ecosystem of Trust demonstrator ISN Site"]
    [:p "Detail on the ISN participants and signal topics can be found here"]]])

(defn body [session & content]
  [:body
   (navbar session)
   [:div {:class "container-fluid"}
    [:div {:class "row"}
     (when (= site-type "mirror") (info-mirror))
     (when (= site-type "isn") (info-isn))
     content]]
   [:div {:class "container-fluid"}
    [:footer
     [:ul {:class "list-horizontal"}
      [:li
       [:a {:class "u-uid u-url" :href "/"}
        [:i {:class "bi bi-house-fill"}]]]
      [:li
       [:a {:rel "me" :class "u-url" :href (:rel-me-github cfg)}
        [:i {:class "bi bi-github"}]]]]
     [:p
      [:small "This site is part of an " [:a {:href (:network-site cfg) :target "_blank"} "EoT ISN"] ", for support please email : "]
      [:small [:a {:name "support"} [:a {:href (str "mailto:" (:support-email cfg))} (:support-email cfg)]]]]
     [:p
      [:small "Built using isn-toolkit v" (get-in cfg [:version :isn-toolkit])]]]]
   ;[:script {:src "//code.jquery.com/jquery.js"}]
   [:script {:src "/js/bootstrap.min.js"}]])

(defn page [session markup] (response (html5 (head) (body session markup))))

(defn card [title & body]
  [:div {:class "card"}
   [:div {:class "card-header"} [:h2 title]]
   [:div {:class "card-body"} body]])

(defn login-view []
  (card "Please log in"
        [:p "Please " [:a {:href "/login"} "login"] " to to see the dashboard"]))

(defn signal-list-item [sig nested]
  (let [obj-inner (if (some #{(:category sig)} #{"isn-participant" "isn-mirror"})
                    [:a {:href (str "https://" (:object sig)) :target "_blank"} (:object sig)]
                    (:object sig))]
    [:div
     [:div
      [:a {:class "p-summary" :href (:permafrag sig)} (:summary sig)]]
     [:div {:class "p-name"} [:b "Object : "] obj-inner]
     (when-not (some #{(:category sig)} #{"isn-participant" "isn-mirror"})
       [:div
        [:b "Expires:"]
        [:span {:class "dt-end"} (:end sig)]
        ", "
        [:b "Priority : "]
        [:span {:class "h-review p-rating"} (or (:priority sig) "N/A")]])
     [:div
      [:b "Provider : "]
      [:a {:class "p-author h-card" :href (str "https://" (:provider sig)) :target "_blank"} (:provider sig)]
      ", "
      [:b "Published : "]
      [:time {:class "dt-published" :datetime (:publishedDateTime sig)} (:publishedDateTime sig)]]]))

(defn signals-list [f-sig-list f-sig-item query-params]
  (let [sorted-xs (f-sig-list {:api? false :filters (or query-params {})})]
    [:div {:class "h-feed"}
     [:ul {:class "list-group"}
      (for [[k v] sorted-xs]
        (let [sorted-sigs (sort-by :publishedDateTime v)
              oldest (first (sort-by :publishedDateTime v))]
          [:li {:class "h-event list-group-item"}
           (f-sig-item (first sorted-sigs) false)
           (when (not-empty (rest sorted-sigs))
             [:ul {:class "list-group"}
              (for [sig (rest sorted-sigs)]
                [:li {:class "h-event thread list-group-item"}
                 [:div
                  [:i {:class "bi bi-list-nested"}] " "
                  [:a {:class "p-summary" :href (:permafrag sig)} (:summary sig)]]])])]))]]))

(defn signal-item [signal-id]
  (let [f-name (str sig-path "/" signal-id ".edn")
        sig (file->edn f-name)]
    [:div {:class "col-lg-9" :role "main"}
     [:div {:class "card"}
      [:div {:class "card-header"}
       [:h2 "Signal detail"]]
      [:div {:class "card-body"}
       [:article {:class "h-event"}
        [:a {:href (str "/" (:permafrag sig)) :class "u-url"}
         [:h2 {:class "p-name"} (:object sig)]]
        [:div
         [:h3 "Signal payload"]
         (when-not (= (:category sig) "isn")

           (for [[k v] (:payload sig)]
             [:div
              [:b (str (name k) " : ")]
              [:span v]]))
         [:h3 "Signal metadata"]
         [:b "Summary: "]
         [:span {:class "p-summary"} (:summary sig)]]
        [:div {:class "h-product"}
         [:div
          [:b "Signal ID: "]
          [:span {:class "u-identified"} (:signalId sig)]
          [:div
           [:b "Correlation ID: "]
           [:span {:class "workflow-correlation"} (:correlation-id sig)]]]]
        (when-not (= (:category sig) "isn")
          [:div
           [:div "Provider mapping: " [:span (:providerMapping sig)]]
           [:div {:class "h-review"}
            [:b "Priority : "]
            [:span {:class "p-rating"} (:priority sig)]]
           [:div
            [:b "Expires : "]
            [:span {:class "dt-end"} (:end sig)]]])
        [:div "Provider : "
         [:a {:href (str "https://" (:provider sig)) :target "_blank" :class "h-card p-name" :rel "author"} (:provider sig)]]
        [:div "Published : "
         [:time {:class "dt-published" :datetime (:publishedDateTime sig)} (:publishedDateTime sig)]]
        [:div "Category : "
         [:span {:class "p-category"} (:category sig)]]
        (when (:syndicated-from sig)
          [:div "Syndicated from : "
           [:a {:href (:syndicated-from sig)} (:provider sig)]])]]]]))

;;;; Views
;;;; ===========================================================================

(defn home [{:keys [session]}]
  (page session
        [:div {:class "col-lg-9" :role "main"}
         (if (or (:user session) (dev?))
           (card "Home"
                 [:p "This is an ISN site - part of an EoT BTD"]
                 [:p "Please go to the " [:a {:href "/dashboard"} "dashboard"] " to to see the signals"])
           (login-view))]))

(defn dashboard [{:keys [query-params session]}]
  (if (or (:user session) (dev?))
    (page session
          [:div {:class "col-lg-9" :role "main"}
           (when (some #{site-type} #{"site" "mirror"})
             (card "Latest signals"
                   [:form {:action "/dashboard" :method "get" :name "filterform"}
                    [:i {:class "bi bi-filter"}] [:input {:id "provider" :name "provider" :placeholder "provider.domain.xyz"}]]
                   [:br]
                   (signals-list sorted-instant-edn signal-list-item query-params)))
           (when (= site-type "isn")
             [:div
              (card "ISN Details"
                    [:ul
                     [:li (str "Name: " (:site-name cfg))]
                     [:li (str "Purpose: " (:isn-purpose cfg))]])
              (card "ISN participants"
                    (signals-list participants-edn signal-list-item query-params))
              (card "Isn mirrors"
                    (signals-list mirrors-edn signal-list-item query-params))])])
    (page session [:div {:class "col-lg-9" :role "main"} (login-view)])))

(defn account [{{:keys [token user] :as session} :session}]
  (if (or user (dev?))
    (page session
          [:div {:class "col-lg-9" :role "main"}
           (card "Account"
                 [:h3 "API Token"]
                 [:p {:class "wrap-break"} token])])
    (page session [:div {:class "col-lg-9" :role "main"} (login-view)])))

(defn login [{:keys [session]}]
  (page session
        [:div {:class "col-lg-9" :role "main"}
         [:h2  "Login"]
         [:form {:action "https://indieauth.com/auth" :method "get"}
          [:label {:for "url"} "Web Address "]
          [:input {:id "url" :type "text" :name "me" :placeholder "https://yourdomain.you"}]
          [:p [:button {:type "submit"} "Sign in"]]
          [:input {:type "hidden" :name "client_id" :value (client-id)}]
          [:input {:type "hidden" :name "redirect_uri" :value (redirect-uri)}]
          [:input {:type "hidden" :name "state" :value "blurb"}]]]))

;; The callback function for indieauth authentication, gets the user's profile plus an access token
;; The means by which we authenticate and associate a user and token into our session
;; https://indieweb.org/obtaining-an-access-token
;; https://indieauth.spec.indieweb.org/#redeeming-the-authorization-code
(defn indieauth-redirect [{:keys [path-params query-params session]}]
  (let [code (:code query-params)
        site (:site path-params)        
        rsp @(client/post "https://tokens.indieauth.com/token" {:headers {"Accept" "application/json"} :form-params {:grant_type "authorization_code" :code code :client_id (client-id) :me (rel-root) :redirect_uri (redirect-uri)}})
        {:keys [me access_token]} (keywordize-keys (json/read-str (:body rsp)))
        user (:host (uri me))        ]
    (if (some #{user} (:authcn-ids cfg))
      (do
        (-> (redirect (:redirect-uri cfg)) (assoc :session {:user user :token access_token})))
      (-> (redirect "/")))))

(defn about [{:keys [session]}]
  (page session
        (if (or (:user session) (dev?))
          (condp = site-type
            "site" (html->hiccup (slurp "resources/public/html/about-site.html"))
            "mirror" (html->hiccup (slurp "resources/public/html/about-mirror.html"))
            "isn" (html->hiccup (slurp "resources/public/html/about-isn.html")))
          (login-view))))

(defn documentation [{:keys [session]}]
  (page session
        (if (or (:user session) (dev?))
          (html->hiccup (slurp "resources/public/html/documentation.html"))
          (login-view))))

(defn signal [{:keys [path-params]}] (page nil (signal-item (:signal-id path-params))))

;;;; API
;;;; ===========================================================================

(defn- token-header->id [headers]
  (let [headers (keywordize-keys headers)
        token (:authorization headers)
        token-body (validate-token token)]
    {:id (get token-body "me") :token token}))

(defn- signals [{:keys [headers query-params] :as req}]
  (let [id (:id (token-header->id headers))
        sorted-xs (sorted-instant-edn {:filters (or query-params {})})]
    (if (and (get-in req [:headers "authorization"]) (authcn? id))
      {:status 200 :headers {"Content-Type" "application/json"} :body (json/write-str sorted-xs)}
      {:status 500 :body "There was a problem fulfilling your request"})))

(defn- make-post [m]
  (let [inst (jt/instant)
        now (jt/local-date)
        published (jt/format "yyyy-MM-dd" now)]
    (-> {}
        (assoc :provider (:author cfg))
        (assoc :publishedDate published)
        (assoc :publishedDateTime (.toString inst)))))

;; Provides extensibility we can publish a growing number of content types or 'posts' e.g. events, notes etc
(defmulti dispatch-post (fn [m] (first (keys (select-keys m [:in-reply-to :like-of :h])))))

(defmethod dispatch-post :h [m] ;; REVIEW: currently defaults to event post type - do we need notes?
  (debug :isn-site/dispatch-post-event {})
  (let [cat (arr-syntax-key m)
        map-data (if (:description m) (keywordize-keys (into {} (map #(split % #"=") (split (:description m) #"\^")))) {})
        corr-id (or (:correlation-id map-data) (str (UUID/randomUUID)))
        sig-id (str (UUID/randomUUID))
        post (make-post m)
        primary-map (-> post
                        (assoc :category cat)
                        (assoc :permafrag (str "signals/" (str (replace (:publishedDate post) "-" "") "-" (first (split  corr-id #"-")) "-" (first (split  sig-id #"-")))))
                        (assoc :object (:name m))
                        (assoc :predicate (:summary m))
                        (assoc :summary (str (:name m)  " " (:summary m)))
                        (assoc :correlation-id corr-id)
                        (assoc :signalId sig-id)
                        (assoc :start (if (blank? (:start m)) (str (jt/instant)) (str->inst (:start m))))
                        (assoc :end (if (blank? (:end m)) (str (jt/instant (jt/plus (jt/instant) (jt/days (:days-from-now cfg))))) (str->inst (:end m))))
                        (assoc :payload map-data))]
    primary-map))

;; https://www.w3.org/TR/micropub/
;; The means by which we publish a signal on to an ISN Site
(defn- micropub [req]
  (let [params (keywordize-keys (:params req))
        {id :id token :token} (token-header->id (:headers req))
        post-data (dispatch-post params)
        loc-hdr (str site-root "/" (:permafrag post-data))]
    (debug :isn/micropub {:params params})
    (debug :isn/micropub {:post-data post-data})
    (if (and (get-in req [:headers "authorization"]) (authcn? id))
      (do
        (its/create pr-fs (str "/" (:permafrag post-data) ".edn") post-data)
        (when (:mp-syndicate-to params)
          (let [synd-uri (str (:mp-syndicate-to params) "/webmention")
                options {:headers (merge {"Authorization" token} form-enc)
                         :form-params {:target synd-uri :source loc-hdr}}]
            @(client/post synd-uri options)))
        {:status 201 :headers {"Location" loc-hdr} :body "post has been created"})
      {:status 500 :body "There was a problem creating your post"})))

;; https://www.w3.org/TR/webmention/
;; The means by which we interact with the posts/contributions of others in the ISN
;; REVIEW: look into async - is it necessary for the kind of load we will be handling?
(defn- webmention [req]
  (let [{id :id token :token} (token-header->id (:headers req))
        params (keywordize-keys (:params req))
        inst (jt/instant)
        dt-pub (.toString inst)
        now (jt/local-date)
        published (jt/format "yyyy-MM-dd" now)
        date-pathfrag (replace published "-" "")
        u-frag (first (split  (str (UUID/randomUUID)) #"-"))
        permafrag (str "signals/" date-pathfrag "-" u-frag)
        loc-hdr (str site-root "/" permafrag)
        sig-src (html-resource (java.net.URL. (:source params)))
        sig-object (selector sig-src [:article.h-event :h2.p-name])
        sig-summary (selector sig-src [:article.h-event :span.p-summary])
        sig-end (selector sig-src [:article.h-event :div :span.dt-end])
        sig-author (selector sig-src [:article.h-event :div :a.p-name])
        sig-category (selector sig-src [:article.h-event :span.p-category])
        sig-id (selector sig-src [:article.h-event :div.h-product :span.u-identified])
        sig-cor (selector sig-src [:article.h-event :div.h-product :span.workflow-correlation])
        sig-pub (selector sig-src [:article.h-event :time.dt-published])
        sig-pubtime (first (mapcat #(attr-values % :datetime) (select sig-src [:article.h-event (attr? :datetime)])))
        sig-priority (selector sig-src [:article.h-event :div.h-review :span.p-rating])
        sig-data {:object sig-object :syndicated-from (:source params) :publishedDateTime sig-pubtime :summary sig-summary :permafrag permafrag :end sig-end :signalId sig-id :correlationId sig-cor :publishedDate sig-pub :priority sig-priority :provider sig-author :category sig-category}]
    (debug :isn/webmention {:params params})
    (debug :isn/webmention {:signal-data sig-data})
    (if (authcn? id)
      (do
        (its/create pr-fs (str "/" permafrag ".edn") sig-data)
        {:status 201
         :headers {"Location" loc-hdr}
         :body "signal has been created"})
      {:status 500})))

;;;; Routes, service, server and app entry point.
;;;; ===========================================================================

(def routes
  #{["/"                   :get  (conj ses-tors `home)]
    ["/login"              :get  (conj ses-tors `login)]
    ["/indieauth-redirect" :get  (conj ses-tors `indieauth-redirect)]
    ["/dashboard"          :get  (conj ses-tors `dashboard)]
    ["/account"            :get  (conj ses-tors `account)]
    ["/signals/:signal-id" :get  (conj htm-tors `signal)]
    ["/about"              :get  (conj ses-tors `about)]
    ["/documentation"      :get  (conj ses-tors `documentation)]
    ["/micropub"           :post (conj api-tors `micropub)]
    ["/webmention"         :post (conj api-tors `webmention)]
    ["/signals"            :get  (conj api-tors `signals)]
    ["/status"             :get status :route-name :status]})

(def service-map {::http/secure-headers {:content-security-policy-settings (:csp-settings cfg)}
                  ::http/routes            routes
                  ::http/type              :jetty
                  ::http/resource-path     "public"
                  ::http/host              "0.0.0.0"
                  ::http/port              (Integer. (or (:port cfg) 5001))
                  ::http/container-options {:h2c? true :h2?  false :ssl? false}})

(defn validate-config []
  (when-not (s/valid? ::config cfg)
    (s/explain ::config cfg)
    (throw (ex-info "Invalid configuration." (s/explain-data ::config cfg))))
  cfg)

;; App entry point
(defn -main [_]
  (validate-config)
  (info :isn/main (str "starting ISN Toolkit instance v" (get-in cfg [:version :isn-toolkit])))
  (info :isn/main (str "site type : " (:site-type cfg)))
  (info :isn/main (str "data-path : " (:data-path cfg)))
  (-> service-map http/create-server http/start))
