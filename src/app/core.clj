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
            [io.pedestal.http.sse :as sse]
            [io.pedestal.log :refer [debug info error]]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as chan]
            [java-time.api :as jt]
            [medley.core :refer [distinct-by]]
            [ring.middleware.session.cookie :as cookie]
            [ring.util.response :refer [redirect response]]
            [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [lambdaisland.uri :refer [uri]]
            [net.cgrand.enlive-html :refer [attr? attr-values html-resource select text]]
            [ten-d-c.hiccup-server-components.core :refer [->html]]
            [voxmachina.itstore.postrepo :as its]
            [voxmachina.itstore.postrepo-fs :as itsfile]
            [ui.layout :refer [htm-tors html->hiccup page ses-tors]]
            [ui.components])
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

(defonce subscribers (atom {}))

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

(def api-tors [(body-params)])

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
        xs (distinct-by #(% :object) xs-isn)]
    (group-by :correlation-id xs)))

(defn- mirrors-edn [{:keys [path api? filters] :or {path sig-path api? false filters {}}}]
  (let [xs-files (filter #(.isFile %) (file-seq (file path)))
        xs-edn (map file->edn (map str xs-files))
        xs-isn (filter #(= (:category %) "isn-mirror") xs-edn)
        xs (distinct-by #(% :name) xs-isn)]
    (group-by :correlation-id xs)))

(defn- selector [src path] (first (map text (select src path))))

(defn- sse-stream-ready [event-chan ctx]
  (let [{{uri :uri {user :user session-id :session-id connection-uuid :connection-uuid} :path-params} :request} ctx]
    ;(info :isn/sse-stream-ready "Starting SSE stream for user" (pr-str user) "session-id" (pr-str session-id))
    (swap! subscribers assoc :connection {:event-channel event-chan :uri uri :session-id session-id})
    (async/>!! event-chan {:name "log-msg" :data "Client has subscribed to ISN SSE stream"})))

(defn- sse-send [msg] (async/>!! (get-in @subscribers [:connection :event-channel]) {:name "isn-signal" :data msg}))

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
  [:nav.navbar.navbar-expand-md.navbar-light.fixed-top.bg-light
   [:div.container-fluid
    [:a.navbar-brand {:href "/"} (:site-name cfg)]
    [:button.navbar-toggler {:type "button" :data-toggle "collapse" :data-target "#navbarCollapse" :aria-controls "navbarCollapse" :aria-expanded "false" :aria-label "Toggle navigation"}
     [:span.navbar-toggler-icon]]
    [:div#navbarCollapse.collapse.navbar-collapse
     [:ul#header-page-links.navbar-nav.mr-auto
      [:li.nav-item [:a.nav-link {:href "/dashboard"} "Dashboard"]]
      [:li.nav-item [:a.nav-link {:href "/about"} "About"]]
      [:li.nav-item [:a.nav-link {:href "/documentation"} "Documentation"]]
      (when user [:li.nav-item [:a.nav-link {:href "/account"} "Account"]])]]]])

(defn info-mirror []
  [:ui.c/alert-info {}
   [:p "This is an Ecosystem of Trust demonstrator ISN Mirror Site"]
   [:p "Topics are published here that are relevant to the ISN participants who collaborate in this ISN"]])

(defn info-isn []
  [:ui.c/alert-success {}
   [:p "This is an Ecosystem of Trust demonstrator ISN Network Site"]
   [:p "Detail on the ISN participants and network mirror sites are provided here"]])

(defn body [session & content]
  [:body
   (navbar session)
   [:div.container-fluid
    [:div.row
     (when (= site-type "mirror") [:div.col-lg-9 {:role "main"} (info-mirror)])
     (when (= site-type "isn") [:div.col-lg-9 {:role "main"} (info-isn)])
     [:div.col-lg-9 {:role "main"} content]]]
   [:div.container-fluid
    [:footer
     [:ul.list-horizontal
      [:li
       [:a.u-uid.u-url {:href "/"}
        [:i.bi.bi-house-fill]]]
      [:li
       [:a.u-url {:rel "me" :href (:rel-me-github cfg)}
        [:i.bi.bi-github]]]]
     [:p
      [:small "This site is part of an " [:a {:href (:network-site cfg) :target "_blank"} "EoT ISN"] ", for support please email : "]
      [:small [:a {:name "support"} [:a {:href (str "mailto:" (:support-email cfg))} (:support-email cfg)]]]]
     [:p
      [:small "Built using isn-toolkit v" (get-in cfg [:version :isn-toolkit]) ". View " [:a {:href "/privacy"} "privacy"] " information."]]]]
   ;[:script {:src "//code.jquery.com/jquery.js"}]
   [:script {:src "/js/bootstrap.min.js"}]])

(defn login-view []
  [:ui.l/card {} "Please log in"
        [:p "Please " [:a {:href "/login"} "login"] " to to see the dashboard"]])

(defn signal-list-item [sig nested]
  (let [obj-inner (if (some #{(:category sig)} #{"isn-participant" "isn-mirror"})
                    [:a {:href (str "https://" (:object sig)) :target "_blank"} (:object sig)]
                    (:object sig))]
    [:div
     [:div
      [:a.p-summary {:href (:permafrag sig)} (:summary sig)]]
     [:div.p-name [:b "Object : "] obj-inner]
     (when-not (some #{(:category sig)} #{"isn-participant" "isn-mirror"})
       [:div
        [:b "Expires:"]
        [:span.dt-end (:end sig)]
        ", "
        [:b "Priority : "]
        [:span.h-review.p-rating (or (:priority sig) "N/A")]])
     [:div
      [:b "Provider : "]
      [:a.p-author.h-card {:href (str "https://" (:provider sig)) :target "_blank"} (:provider sig)]
      ", "
      [:b "Published : "]
      [:time.dt-published {:datetime (:publishedDateTime sig)} (:publishedDateTime sig)]]]))

(defn signals-list [f-sig-list f-sig-item query-params]
  (let [sorted-xs (f-sig-list {:api? false :filters (or query-params {})})]
    [:div.h-feed
     [:ul.list-group
      (for [[k v] sorted-xs]
        (let [sorted-sigs (sort-by :publishedDateTime v)
              oldest (first (sort-by :publishedDateTime v))]
          [:li.h-event.list-group-item
           (f-sig-item (first sorted-sigs) false)
           (when (not-empty (rest sorted-sigs))
             [:ul.list-group
              (for [sig (rest sorted-sigs)]
                [:li.h-event.thread.list-group-item
                 [:div
                  [:i.bi.bi-list-nested] " "
                  [:a.p-summary {:href (:permafrag sig)} (:summary sig)]]])])]))]]))

(defn signal-item [signal-id]
  (let [f-name (str sig-path "/" signal-id ".edn")
        sig (file->edn f-name)]
     [:div.card
      [:div.card-header
       [:h2 "Signal detail"]]
      [:div.card-body
       [:article.h-event
        [:a.u-url {:href (str "/" (:permafrag sig))}
         [:h2.p-name (:object sig)]]
        [:div
         [:h3 "Signal payload"]
         (when-not (= (:category sig) "isn")
           (for [[k v] (:payload sig)]
             [:div
              [:b (str (name k) " : ")]
              [:span v]]))
         [:h3 "Signal metadata"]
         [:b "Summary: "]
         [:span.p-summary (:summary sig)]]
        [:div.h-product
         [:div
          [:b "Signal ID: "]
          [:span.u-identified (:signalId sig)]
          [:div
           [:b "Correlation ID: "]
           [:span.workflow-correlation (:correlation-id sig)]]]]
        (when-not (= (:category sig) "isn")
          [:div
           [:div "Provider mapping: " [:span (:providerMapping sig)]]
           [:div.h-review
            [:b "Priority : "]
            [:span.p-rating (:priority sig)]]
           [:div
            [:b "Expires : "]
            [:span.dt-end (:end sig)]]])
        [:div "Provider : "
         [:a.h-card.p-name {:href (str "https://" (:provider sig)) :target "_blank" :rel "author"} (:provider sig)]]
        [:div "Published : "
         [:time.dt-published {:datetime (:publishedDateTime sig)} (:publishedDateTime sig)]]
        [:div "Category : "
         [:span.p-category (:category sig)]]
        (when (:syndicated-from sig)
          [:div "Syndicated from : "
           [:a {:href (:syndicated-from sig)} (:provider sig)]])]]]))

;;;; Views
;;;; ===========================================================================

(defn home [{:keys [session]}]
  (page session head body
        (if (or (:user session) (dev?))
          [:ui.l/card {} "Home"
           [:p "This is an ISN Participant Site - part of an EoT BTD"]
           [:p "Please go to the " [:a {:href "/dashboard"} "dashboard"] " to to see the signals"]]
          (login-view))))

(defn dashboard [{:keys [query-params session]}]
  (if (or (:user session) (dev?))
    (page session head body
          (when (some #{site-type} #{"site" "mirror"})
            [:ui.l/card {} "Latest signals"
             [:form {:action "/dashboard" :method "get" :name "filterform"}
              [:i.bi.bi-filter] [:input#provider {:name "provider" :placeholder "provider.domain.xyz"}]]
             [:br]
             (signals-list sorted-instant-edn signal-list-item query-params)])
          (when (= site-type "isn")
            [:div
             [:ui.l/card {} "ISN Details"
              [:ul
               [:li (str "Name: " (:site-name cfg))]
               [:li (str "Purpose: " (:isn-purpose cfg))]]]
             [:ui.l/card {}  "ISN participants" (signals-list participants-edn signal-list-item query-params)]
             [:ui.l/card {}  "ISN mirrors"      (signals-list mirrors-edn signal-list-item query-params)]]))
    (page session head body (login-view))))

(defn account [{{:keys [token user] :as session} :session}]
  (if (or user (dev?))
    (page session head body
          [:ui.l/card {}  "Account"
           [:h3 "API Token"]
           [:p.wrap-break token]])
    (page session head body (login-view))))

(defn login [{:keys [session]}]
  (page session head body
        [:h2  "Login"]
        [:form {:action "https://indieauth.com/auth" :method "get"}
         [:label {:for "url"} "Web Address "]
         [:input#url {:type "text" :name "me" :placeholder "https://yourdomain.you"}]
         [:p [:button {:type "submit"} "Sign in"]]
         [:input {:type "hidden" :name "client_id" :value (client-id)}]
         [:input {:type "hidden" :name "redirect_uri" :value (redirect-uri)}]
         [:input {:type "hidden" :name "state" :value "blurb"}]]))

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
  (page session head body
        (if (or (:user session) (dev?))
          (condp = site-type
            "site" (html->hiccup (slurp "resources/public/html/about-site.html"))
            "mirror" (html->hiccup (slurp "resources/public/html/about-mirror.html"))
            "isn" (html->hiccup (slurp "resources/public/html/about-isn.html")))
          (login-view))))

(defn documentation [{:keys [session]}]
  (page session head body
        (if (or (:user session) (dev?))
          (html->hiccup (slurp "resources/public/html/documentation.html"))
          (login-view))))

(defn privacy [{:keys [session]}] (page session head body (html->hiccup (slurp "resources/public/html/privacy.html"))))

(defn signal [{:keys [path-params]}] (page nil head body (signal-item (:signal-id path-params))))

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
        (sse-send {:name "isn-signal" :data post-data})
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
    ["/privacy"            :get  (conj htm-tors `privacy)]
    ["/micropub"           :post (conj api-tors `micropub)]
    ["/webmention"         :post (conj api-tors `webmention)]
    ["/signals"            :get  (conj api-tors `signals)]
    ["/status"             :get status :route-name :status]
    ["/stream"             :get (sse/start-event-stream sse-stream-ready) :route-name :stream]})

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
