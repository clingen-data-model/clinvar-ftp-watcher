(ns watcher.gcpbucket
  "GCP bucket operations"
  (:require [watcher.util       :as util]
            [clojure.data.json  :as json]
            [clojure.instant    :as instant]
            [clojure.java.io    :as io]
            [clojure.string     :as str]
            [hickory.core       :as html]
            [hickory.select     :as css]
            [org.httpkit.client :as http])
  (:import [com.google.auth.oauth2 GoogleCredentials]))


(def ^:private staging-bucket
  "Name of the bucket where Monster ingest stages the ClinVar files."
  "broad-dsp-monster-clingen-prod-staging-storage")

(def ^:private api-url
  "The Google Cloud API URL."
  "https://www.googleapis.com/")

(def ^:private storage-url
  "The Google Cloud URL for storage operations."
  (str api-url "storage/v1/"))

(def ^:private bucket-url
  "The Google Cloud Storage URL for bucket operations."
  (str storage-url "b/"))

(def ^:private google-adc-token
  "Nil or a token for the Application Default Credentials."
  (delay
    (some-> (GoogleCredentials/getApplicationDefault)
            (.createScoped
             ["https://www.googleapis.com/auth/devstorage.read_only"])
            .refreshAccessToken .getTokenValue)))

(defn ^:private list-prefixes
  "Return all names in BUCKET with PREFIX in a lazy sequence."
  ([bucket prefix]
   (let [params  {:delimiter "/" :maxResults 999 :prefix prefix}
         request {:as           :stream
                  :content-type :application/json
                  :headers      (util/auth-header @google-adc-token)
                  :method       :get
                  :query-params params
                  :url          (str bucket-url bucket "/o")}]
     (letfn [(each [pageToken]
               (let [{:keys [nextPageToken prefixes]}
                     (-> request
                         (assoc-in [:query-params :pageToken] pageToken)
                         http/request deref :body io/reader util/parse-json)]
                 (lazy-cat prefixes
                           (when nextPageToken (each nextPageToken)))))]
       (each ""))))
  ([bucket]
   (list-prefixes bucket "")))

(defn latest-staged
  "Return the latest timestamp from staging BUCKET."
  [bucket]
  (let [regex #"^(\d\d\d\d)(\d\d)(\d\d)T(\d\d)(\d\d)(\d\d)/$"]
    (letfn [(instify [prefix]
              (let [[ok YYYY MM DD hh mm ss] (re-matches regex prefix)]
                (when ok
                  (instant/read-instant-timestamp
                   (str YYYY \- MM \- DD \T hh \: mm \: ss)))))]
      (->> bucket list-prefixes (map instify) sort last))))

