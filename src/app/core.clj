(ns app.core
  (:refer-clojure :exclude [replace])
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :refer [file]]
            [clojure.spec.alpha :as s]
            [clojure.string :refer [blank? includes? replace split trim]]
            [clojure.walk :refer [keywordize-keys]]
            [aero.core :refer (read-config)]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :refer [body-params]]
            [io.pedestal.http.sse :as sse]
            [io.pedestal.log :refer [debug info]]
            [clojure.core.async :as async]
            [java-time.api :refer [after? before? days format instant local-date local-date-time plus zoned-date-time]]
            [medley.core :refer [distinct-by]]
            [ring.util.response :refer [redirect]]
            [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [lambdaisland.uri :refer [uri]]
            [net.cgrand.enlive-html :refer [attr? attr-values html-resource select text]]
            [voxmachina.itstore.postrepo :as its]
            [voxmachina.itstore.postrepo-fs :as itsfile]
            [ui.layout :refer [->201 ->400 ->401 ->500 htm-tor html->hiccup page ses-tor]]
            [ui.components])
  (:import java.util.UUID))

;;;; Config and constants
;;;; ===========================================================================

(alter-var-root #'org.httpkit.client/*default-client* (fn [_] sni-client/default-client))

(def dt-fmt "dd-MM-yyyy HH:mm:ss")

(def config (read-config "config.edn" {}))

(def data-path (:data-path config))

(def sig-path (str data-path "/signals"))

(defn dev? [] (= (:environment config) "dev"))

(def site-root (:site-root config))

(def site-type (:site-type config))

(def mirror-cat "isn-membership-mirror-update")

(def participant-cat "isn-membership-participant-update")

(def meta-site-type #{participant-cat mirror-cat})

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
(s/def ::indieauth-token-uri s-uri?)
(s/def ::config (s/keys :req-un [::site-name ::site-root ::authcn-ids ::indieauth-token-uri ::data-path ::dev-site]))

(defn- status [req] {:status 200 :body "Service is running"})

(def api-tors [(body-params)])

(defn rel-root [] (:rel-root config)) ;; REVIEW: use function to provision for multi-tenancy

(defn client-id [] (str (rel-root) "/"))

(defn redirect-uri [] (str (client-id) "indieauth-redirect"))

(defn login-uri [] (:indielogin-uri config))

(defn- validate-token [token]
  (if (dev?)
    {"me" (:dev-site config)}
    (let [rsp @(client/get (:indieauth-token-uri config) {:headers {"Authorization" token "Accept" "application/json"}})] 
      (json/read-str (:body rsp)))))

(defn- authcn? [id]
  (let [host (trim (:host (uri id)))
        ids (:authcn-ids config)]
    (and (not-empty ids) host (some #{host} ids))))

(defn file->edn [file] (->> file slurp edn/read-string))

(defn make-filter [[k v]] (filter #(or (= v (k %)) (= v (get-in % [:payload k])))))

(defn make-category-filter [] ())

(defn- sorted-instant-edn [{:keys [path api? filters] :or {path sig-path api? true filters {}}} category]
  (let [{:keys [category isn from to] :or {category nil isn nil from nil to nil}} filters
        xs-files (filter #(.isFile %) (file-seq (file path)))
        xs-edn (map file->edn (map str xs-files))
        current-xs (remove #(before? (instant (:end %)) (instant)) xs-edn)
        fs-xs (try (sequence (reduce comp (map make-filter (dissoc filters :from :to :isn :category))) current-xs) (catch Exception e  current-xs))
        xs (if (and from to)
             (remove #(or (before? (instant (:publishedDateTime %)) (instant from)) (after? (instant (:publishedDateTime %)) (instant to))) fs-xs)
             fs-xs)
        cat-xs (if category (filter #(some (:category %) #{category}) xs) xs)
        isn-xs (if isn (filter #(some (:category %) #{isn}) cat-xs ) cat-xs)
        sigs (if api? (map #(dissoc % :permafrag :summary) isn-xs) isn-xs)] ; REVIEW: sort signal by pubish date time somewhere?
    (group-by :correlation-id sigs)))

(defn- str->inst [x]
  (if (includes? x "T")
    (str (instant x))
    (str (instant (zoned-date-time (local-date-time dt-fmt x) "UTC")))))

(defn- isn-membership-edn [{:keys [path api? filters] :or {path sig-path api? false filters {}}} cat]
  (let [xs-files (filter #(.isFile %) (file-seq (file path)))
        xs-edn (map file->edn (map str xs-files))
        xs-isn (filter #(some (:category %) #{cat}) xs-edn)
        xs (distinct-by #(% :object) xs-isn)]
    (group-by :correlation-id xs)))

(defn- selector [src path] (first (map text (select src path))))

(defn- sse-send [msg] (doseq [[k v] @subscribers] (async/>!! (:event-channel v) {:name "isn-signal" :data msg})))

;;;; Service interceptors
;;;; ===========================================================================
(def cfg-tor {:name :cfg-tor :enter (fn [context] (assoc-in context [:request :cfg] config))})

;;;; Components
;;;; ===========================================================================

(defn head [{:keys [cfg] :as req}]
  [:html [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:link {:rel "authorization_endpoint" :href "https://indieauth.com/auth"}]
   [:link {:rel "token_endpoint" :href (:indieauth-token-uri cfg)}]
   [:link {:rel "micropub" :href (str rel-root "/micropub")}]
   [:link {:rel "webmention" :href (str (:rel-root cfg) "/webmention")}]
   [:link {:rel "microsub" :href (:microsub-uri cfg)}]
   [:link {:rel "stylesheet" :type "text/css" :href "/css/bootstrap.min.css"}]
   [:link {:rel "stylesheet" :type "text/css" :href "/css/bootstrap-icons.css"}]
   [:link {:rel "stylesheet" :type "text/css" :href "/css/style.css"}]
   [:title (:site-name cfg)]]])

(defn navbar [{:keys [cfg session]}]
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
      (when (:user session) [:li.nav-item [:a.nav-link {:href "/account"} "Account"]])]]]])

(defn info-mirror []
  [:ui.c/alert-info {}
   [:p "This is an Ecosystem of Trust demonstrator ISN Mirror Site"]
   [:p "Topics are published here that are relevant to the ISN participants who collaborate in this ISN"]])

(defn info-network []
  [:ui.c/alert-success {}
   [:p "This is an Ecosystem of Trust demonstrator ISN Network Site"]
   [:p "Detail on the ISN participants and network mirror sites are provided here"]])

(defn body [{:keys [cfg session] :as req} & content]
  [:body
   (navbar req)
   [:div.container-fluid
    [:div.row
     (when (= site-type "mirror") [:div.col-lg-9 {:role "main"} (info-mirror)])
     (when (= site-type "network") [:div.col-lg-9 {:role "main"} (info-network)])
     [:div.col-lg-9 {:role "main"} content]]]
   [:div.container-fluid
    [:footer
     [:ul.list-horizontal
      [:li [:a.u-uid.u-url {:href "/"} [:i.bi.bi-house-fill]]]
      [:li [:a.u-url {:rel "me" :href (:rel-me-github cfg)} [:i.bi.bi-github]]]]
     [:p
      [:small "This site is part of an " [:a {:href (:network-site cfg) :target "_blank"} "EoT ISN"] ", for support please email : "]
      [:small [:a {:name "support"} [:a {:href (str "mailto:" (:support-email cfg))} (:support-email cfg)]]]]
     [:p
      [:small "Built using isn-toolkit v" (get-in cfg [:version :isn-toolkit]) ". This software uses the " [:a {:href "https://opensource.org/license/mit/"} "MIT"] " licence. View " [:a {:href "/privacy"} "privacy"] " information."]]]]
   ;[:script {:src "//code.jquery.com/jquery.js"}]
   [:script {:src "/js/bootstrap.min.js"}]])

(defn login-view []
  [:ui.l/card {} "Please log in"
        [:p "Please " [:a {:href "/login"} "login"] " to to see the dashboard"]])

(defn signal-list-item [sig]
  (let [obj-inner (if (some (:category sig) #{"isn-participant" "isn-mirror"})
                    [:a {:href (str "https://" (:object sig)) :target "_blank"} (:object sig)]
                    (:object sig))]
    [:div
     [:div [:a.p-summary {:href (:permafrag sig)} (:summary sig)]]
     [:ul
      (for [[k v] (select-keys (:payload sig) (:show-list-payload-keys config))]
        [:li [:b k] ": " v])]
     [:div
      (when (and (:start sig) (:show-eta config)) [:div [:b "ETA : "] [:span (:start sig)]])
      [:b "Provider : "]
      [:a.p-author.h-card {:href (str "https://" (:provider sig)) :target "_blank"} (:provider sig)]
      ", "
      [:b "Published : "]
      [:time.dt-published {:datetime (:publishedDateTime sig)} (:publishedDateTime sig)]]]))

(defn signals-list [f-sig-list f-sig-item query-params category]
  (let [sorted-xs (f-sig-list {:api? false :filters (or query-params {})} category)]
    [:div.h-feed
     [:ul.list-group
      (for [[k v] sorted-xs]
        (let [sorted-sigs (sort-by :publishedDateTime v)]
          [:li.h-event.list-group-item
           (f-sig-item (first sorted-sigs))
           (when (not-empty (rest sorted-sigs))
             [:ul.list-group
              (for [sig (rest sorted-sigs)]
                [:li.h-event.thread.list-group-item
                 [:div
                  [:i.bi.bi-list-nested] " "
                  [:a.p-summary {:href (:permafrag sig)} (:summary sig)]]])])]))]]))

(defn signal-item [signal-id]
  (let [f-name (str sig-path "/" signal-id ".edn")
        {:keys [category payload provider providerMapping publishedDateTime start syndicated-from] :as sig} (file->edn f-name)]
    (println "categories : " category)
     [:div.card
      [:div.card-header [:h2 "Signal detail"]]
      [:div.card-body
       [:article.h-event
        [:a.u-url {:href (str "/" (:permafrag sig))} [:h2.p-name (:object sig)]]
        [:div
         (when-not (some category meta-site-type)
           [:h3 "Signal payload"]
           (for [[k v] payload] [:div [:b (str (name k) " : ")] [:span v]]))
         [:h3 "Signal metadata"]
         [:b "Summary: "] [:span.p-summary (:summary sig)]]
        [:div.h-product
         [:div
          [:b "Signal ID: "] [:span.u-identified (:signalId sig)]
          [:div
           [:b "Correlation ID: "] [:span.workflow-correlation (:correlation-id sig)]]]]
        (when-not (some category meta-site-type)
          [:div
           (when providerMapping [:div "Provider mapping: " [:span providerMapping]])
           [:div.h-review [:b "Priority : "] [:span.p-rating (:priority sig)]]
           [:div [:b "Expires : "] [:span.dt-end (:end sig)]]])
        (when (and start (:show-eta config)) [:div [:b "ETA : "] [:span start]])
        [:div "Provider : "
         [:a.h-card.p-name {:href (str "https://" provider) :target "_blank" :rel "author"} provider]]
        [:div "Published : " [:time.dt-published {:datetime publishedDateTime} publishedDateTime]]
        [:div "Category : " [:span.p-category category]]
        (when syndicated-from [:div "Syndicated from : " [:a {:href syndicated-from} provider]])]]]))

;;;; Views
;;;; ===========================================================================

(defn home [{:keys [cfg session] :as req}]
  (page req head body
        (if (or (:user session) (dev?))
          [:ui.l/card {} "Home"
           [:h2 "About"]
           [:p "This is an ISN Site. It is configured for participants to share signals across specific ISNs."]
           [:p "You will need to be a member of an ISN to view or create signals within it. If you cannot see any signals or create them on this site please see the support links at the bottom of this page."]
           [:ul
            (for [[k v] (:signals cfg)]
              [:li "ISN: " (name k)
               [:ul
                (for [[l u] v]
                  [:li "Signal: " l " - " (:description u)])]])]
           [:p "Please go to the " [:a {:href "/dashboard"} "dashboard"] " to to see the signals"]]
          (login-view))))

(defn dashboard [{:keys [cfg query-params session] :as req}]
  (if (or (:user session) (dev?))
    (page req head body
          (when (some #{site-type} #{"participant" "mirror"})
            [:ui.l/card {} "Latest signals"
             [:form {:action "/dashboard" :method "get" :name "filterform"}
              [:i.bi.bi-filter] [:input#provider {:name "provider" :placeholder "provider.domain.xyz"}]]
             [:br]
             (signals-list sorted-instant-edn signal-list-item query-params nil)])
          (when (= site-type "network")
            [:div
             [:ui.l/card {} "ISN Details"
              [:ul
               [:li (str "Name: " (:site-name cfg))]
               [:li (str "Purpose: " (:isn-purpose cfg))]]]
             [:ui.l/card {}  "ISN Participants" (signals-list isn-membership-edn signal-list-item query-params participant-cat)]
             [:ui.l/card {}  "ISN Mirrors"      (signals-list isn-membership-edn signal-list-item query-params mirror-cat)]]))
    (page req head body (login-view))))

(defn account [{{:keys [token user] :as session} :session :as req}]
  (if (or user (dev?))
    (page req head body
          [:ui.l/card {}  "Account"
           [:h3 "API Token"]
           [:p.wrap-break token]])
    (page req head body (login-view))))

(defn login [{:keys [session] :as req}]
  (page req head body
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
(defn indieauth-redirect [{:keys [cfg path-params query-params session]}]
  (let [code (:code query-params)
        rsp @(client/post (:indieauth-token-uri cfg) {:headers {"Accept" "application/json"} :form-params {:grant_type "authorization_code" :code code :client_id (client-id) :me (rel-root) :redirect_uri (redirect-uri)}})
        {:keys [me access_token]} (keywordize-keys (json/read-str (:body rsp)))
        user (:host (uri me))        ]
    (if (some #{user} (:authcn-ids cfg))
      (-> (redirect (:redirect-uri cfg)) (assoc :session {:user user :token access_token}))
      (-> (redirect "/")))))

(defn about [{:keys [session] :as req}]
  (page req head body
        (if (or (:user session) (dev?))
          (condp = site-type
            "participant" (html->hiccup (slurp "resources/public/html/about-site.html"))
            "mirror" (html->hiccup (slurp "resources/public/html/about-mirror.html"))
            "network" (html->hiccup (slurp "resources/public/html/about-isn.html")))
          (login-view))))

(defn documentation [{:keys [session] :as req}]
  (page req head body
        (if (or (:user session) (dev?))
          (html->hiccup (slurp "resources/public/html/documentation.html"))
          (login-view))))

(defn privacy [{:keys [session] :as req}] (page req head body (html->hiccup (slurp "resources/public/html/privacy.html"))))

(defn signal [{:keys [path-params] :as req}] (page req head body (signal-item (:signal-id path-params))))

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
  (let [inst (instant)
        now (local-date)
        published (format "yyyy-MM-dd" now)]
    (-> {}
        (assoc :provider (:provider m))
        (assoc :publishedDate published)
        (assoc :publishedDateTime (.toString inst)))))

;; Provides extensibility we can publish a growing number of content types or 'posts' e.g. events, notes etc
(defmulti dispatch-post (fn [m] (first (keys (select-keys m [:in-reply-to :like-of :h])))))

(defmacro if-let*
  ([bindings then]
   `(if-let* ~bindings ~then nil))
  ([bindings then else]
   (if (seq bindings)
     `(if-let [~(first bindings) ~(second bindings)]
        (if-let* ~(drop 2 bindings) ~then ~else)
        ~else)
     then)))

(defmethod dispatch-post :h [m] ;; REVIEW: currently defaults to event post type - do we need notes?
  (debug :isn-site/dispatch-post-event {})
  (if-let* [cat (:category m)
            isn-cat (first (filter #(includes? % "isn@") cat))
            isn (keyword (subs isn-cat 4))
            sig-conf (get-in config [:signals isn])]
    (let [map-data (if (:description m) (keywordize-keys (into {} (map #(split % #"=") (split (:description m) #"\^")))) {})
          corr-id (or (:correlation-id map-data) (str (UUID/randomUUID)))
          sig-id (str (UUID/randomUUID))
          domain-cat (keyword (first (remove #(includes? % "isn@") cat)))
          sig-expiry (get-in sig-conf [domain-cat :expiry-days-from-now])
          post (make-post m)
          primary-map (-> post
                          (assoc :category (if (vector? cat) (into #{} cat) (if (nil? cat) nil (conj #{} cat))))
                          (assoc :permafrag (str "signals/" (str (replace (:publishedDate post) "-" "") "-" (first (split  corr-id #"-")) "-" (first (split  sig-id #"-")))))
                          (assoc :object (:name m))
                          (assoc :predicate (:summary m))
                          (assoc :summary (str (:name m)  " " (:summary m)))
                          (assoc :correlation-id corr-id)
                          (assoc :signalId sig-id)
                          (assoc :end (if (blank? (:end m)) (str (instant (plus (instant) (days sig-expiry)))) (str->inst (:end m))))
                          (assoc :payload map-data))
          with-start-map (if-let [start (:start m)] (assoc primary-map :start (str->inst start)) primary-map)]
      with-start-map)
    {}))

(defmethod dispatch-post :default [m] {})

;; https://www.w3.org/TR/micropub/
;; The means by which we publish a signal on to an ISN Site
(defn- micropub [{:keys [headers json-params params] :as req}]
  (let [{:keys [mp-syndicated-to] :as params-kw} (keywordize-keys (or json-params params))
        {id :id token :token} (token-header->id headers)
        provider (trim (:host (uri id)))
        {:keys [permafrag] :as post-data} (dispatch-post (assoc params-kw :provider provider))
        in (cond (nil? (headers "authorization")) :400 (not (authcn? id)) :401 (empty? post-data) :400 :else :201)]
    (condp = in
      :400 (->400 "bad request - please check your request is spec compliant")
      :401 (->401 "unauthorized - credentials or token not valid")
      :201 (let [loc-hdr (str site-root "/" permafrag)]
             (its/create pr-fs (str "/" permafrag ".edn") post-data)
             (sse-send (json/write-str {:name "isn-signal" :data post-data}))
             (->201 loc-hdr "post has been created")))))

(defn- sse-stream-ready [event-chan {:keys [request]}]
  (let [{uri :uri {client :client connection-uuid :connection-uuid} :path-params headers :headers} request
        id (:id (token-header->id headers))]
    (if (and (get-in request [:headers "authorization"]) (authcn? id))
      (do 
        (swap! subscribers assoc (keyword (str client connection-uuid)) {:event-channel event-chan :uri uri})
        (async/>!! event-chan {:name "log-msg" :data "Client has subscribed to ISN SSE stream"}))
      (async/>!! event-chan {:name "log-msg" :data "Error - client could not be subscribed to ISN SSE stream"}))))

;;;; Routes, service, server and app entry point.
;;;; ===========================================================================

(def routes
  #{["/"                                    :get  (conj ses-tor `cfg-tor `home)]
    ["/login"                               :get  (conj ses-tor `cfg-tor `login)]
    ["/indieauth-redirect"                  :get  (conj ses-tor `cfg-tor `indieauth-redirect)]
    ["/dashboard"                           :get  (conj ses-tor `cfg-tor `dashboard)]
    ["/account"                             :get  (conj ses-tor `cfg-tor `account)]
    ["/signals/:signal-id"                  :get  (conj htm-tor `cfg-tor `signal)]
    ["/about"                               :get  (conj ses-tor `cfg-tor `about)]
    ["/documentation"                       :get  (conj ses-tor `cfg-tor `documentation)]
    ["/privacy"                             :get  (conj htm-tor `cfg-tor `privacy)]
    ["/micropub"                            :post (conj api-tors `cfg-tor `micropub)]
    ["/signals"                             :get  (conj api-tors `cfg-tor `signals)]
    ["/status"                              :get status :route-name :status]
    ["/stream/sse/:client/:connection-uuid" :get (sse/start-event-stream sse-stream-ready) :route-name :stream]})

(defn service-map [{:keys [csp-settings port]}]
  {::http/secure-headers {:content-security-policy-settings csp-settings}
   ::http/routes            routes
   ::http/type              :jetty
   ::http/resource-path     "public"
   ::http/host              "0.0.0.0"
   ::http/port              (Integer. (or port 5001))
   ::http/container-options {:h2c? true :h2?  false :ssl? false}})

(defn validate-config [cfg]
  (when-not (s/valid? ::config cfg)
    (s/explain ::config cfg)
    (throw (ex-info "Invalid configuration." (s/explain-data ::config cfg))))
  cfg)

;; App entry point
(defn -main [_]
  (validate-config config)
  (info :isn/main (str "starting ISN Toolkit instance v" (get-in config [:version :isn-toolkit])))
  (info :isn/main (str "site-type : " (:site-type config)))
  (info :isn/main (str "data-path : " (:data-path config)))
  (-> (service-map config) http/create-server http/start))
