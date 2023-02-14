(ns injest
  "Mistakenly ingest stuff."
  (:require [clojure.data.json  :as json]
            [clojure.instant    :as instant]
            [clojure.java.io    :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint     :refer [pprint]]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str]
            [hickory.core       :as html]
            [hickory.select     :as css]
            [org.httpkit.client :as http])
  (:import [com.google.auth.oauth2 GoogleCredentials]
           [com.google.cloud.functions CloudEventsFunction]
           [io.cloudevents CloudEvent]
           [java.text SimpleDateFormat]
           [java.util Date Properties]
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecord ConsumerRecords]
           [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.common PartitionInfo TopicPartition])
  (:gen-class))

(defmacro dump
  "Dump [EXPRESSION VALUE] where VALUE is EXPRESSION's value."
  [expression]
  `(let [x# ~expression]
     (do
       (pprint ['~expression x#])
       x#)))

(s/def ::table
  (s/and sequential?
         (partial every? sequential?)
         #(== 1 (count (set (map count %))))
         #(== (count (set (first %))) (count (first %)))))

(def ^:private ftp-site
  "FTP site of the National Library of Medicine."
  "https://ftp.ncbi.nlm.nih.gov")

;; The FTP/HTTP server requires a final / on URLs.
;;
(def ^:private weekly-url
  "The weekly_release URL."
  (str/join
   "/"
   [ftp-site "pub" "clinvar" "xml" "clinvar_variation" "weekly_release" ""]))

(def ^:private staging-bucket
  "Name of the bucket where Monster ingest stages the ClinVar files."
  "broad-dsp-monster-clingen-prod-staging-storage")

(defn ^:private shell
  "Run ARGS in a shell and return stdout or throw."
  [& args]
  (let [{:keys [exit err out]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (format "injest: %s exit status from: %s : %s"
                              exit args err))))
    (str/trim out)))

(defn parse-json
  "Parse STREAM as JSON or print it."
  [stream]
  (try (json/read stream :key-fn keyword)
       (catch Throwable x
         (pprint {:exception x :stream (slurp stream)})
         stream)))

(defn ^:private tabulate
  "Return a vector of vectors from FILE as a Tab-Separated-Values table."
  [file]
  (letfn [(split [line] (str/split line #"\t"))]
    (-> file io/reader line-seq
        (->> (map split)))))

(defn ^:private mapulate
  "Return a sequence of labeled maps from the TSV TABLE."
  [table]
  {:pre [(s/valid? ::table table)]}
  (let [[header & rows] table]
    (map (partial zipmap header) rows)))

(defn ^:private clinvar_releases_pre_20221027 ;; Larry supplies TSV file
  "Return the TSV file as a sequence of maps."
  []
  (-> "./injest/clinvar_releases_pre_20221027.tsv"
      tabulate mapulate))

(defn ^:private fetch-ftp
  "Return the FTP site at the HTTP URL as a hicory tree."
  [url]
  (-> {:as     :text
       :method :get
       :url    url}
      http/request deref :body html/parse html/as-hickory))

(def ^:private ftp-time
  "This is how the FTP site timestamps."
  (SimpleDateFormat. "yyyy-MM-dd kk:mm:ss"))

(def ^:private ftp-time-ymdhm
  "And sometimes THIS is how the FTP site timestamps."
  (SimpleDateFormat. "yyyy-MM-dd kk:mm"))

(defn ^:private instify
  "Parse string S as a date and return its Instant or NIL."
  [s]
  (try (.parse ftp-time s) (catch Throwable _)))

(defn ^:private instify-ymdhm
  "Parse string S as a date differently and return its Instant or NIL."
  [s]
  (try (.parse ftp-time-ymdhm s) (catch Throwable _)))

(defn ^:private longify
  "Return S or S parsed into a Long after stripping commas."
  [s]
  (or (try (-> s (str/replace "," "") parse-long)
           (catch Throwable _))
      s))

(defn ^:private fix-ftp-map
  "Fix the FTP map entry M by parsing its string values."
  [m]
  (-> m
      (update "Size"          longify)
      (update "Released"      instify)
      (update "Last Modified" instify)
      (update "Last modified" instify-ymdhm) ; Programmers suck.
      (->> (remove (comp nil? second))
           (into {}))))

;; This dispatch function is an HACK.
;;
(defmulti parse-ftp
  "Parse this FTP site's hickory CONTENT and MAPULATE it."
  (comp :type first :content))

;; Handle 4-column FTP fetches with directories and files.
;;
(defmethod parse-ftp :element parse-4
  [content]
  (letfn [(span?   [elem] (-> elem :attrs :colspan))
          (unelem  [elem] (if (map? elem) (-> elem :content first) elem))]
    (let [selected (css/select
                    (css/or
                     (css/child (css/tag :thead) (css/tag :tr) (css/tag :th))
                     (css/child (css/tag :tr) (css/tag :td)))
                    content)
          span (->> selected (keep span?) first parse-long)]
      (->> selected
           (remove span?)
           (map (comp unelem first :content))
           (partition-all span)
           mapulate
           (map fix-ftp-map)))))

;; Handle 3-column FTP fetches with only directories.
;; The middle group is the 'Last modified' FTP-TIME-YMDHM timestamp.
;;
(defmethod parse-ftp :document-type parse-3
  [content]
  (let [regex #"^\s*(.*)\s*\t\s*(\d\d\d\d\-\d\d\-\d\d \d\d:\d\d)\s+(\S+)\s*$"
        [top & rows] (->> content
                          (css/select (css/child (css/tag :pre)))
                          first :content)
        header (map str/trim (str/split top #"     *"))]
    (letfn [(unelem [elem] (if (map? elem) (-> elem :content first) elem))
            (break  [line] (->> line (re-matches regex) rest))]
      (->> rows
           (keep unelem)
           (partition-all 2)
           (map (partial str/join \tab))
           rest
           (map break)
           (cons header)
           mapulate
           (map fix-ftp-map)))))

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
                  :headers      (auth-header @google-adc-token)
                  :method       :get
                  :query-params params
                  :url          (str bucket-url bucket "/o")}]
     (letfn [(each [pageToken]
               (let [{:keys [nextPageToken prefixes]}
                     (-> request
                         (assoc-in [:query-params :pageToken] pageToken)
                         http/request deref :body io/reader parse-json)]
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

(defn ftp-since
  "Return files from WEEKLY-URL more recent than INSTANT in a vector."
  [instant]
  (letfn [(since? [file]
            (apply < (map inst-ms [instant (file "Last Modified")])))]
    (->> weekly-url fetch-ftp parse-ftp rest (filter since?) vec)))

(def ^:private slack-manifest ;; new style slack api - doesn't work
  "The Slack Application manifiest for ClinVar FTP Watcher."
  (let [long (str/join \space ["Notify the genegraph-dev team"
                               "when new files show up in the"
                               weekly-url
                               "FTP site"
                               "before the Monster Ingest team notices."])]
    {:_metadata
     {:major_version 1
      :minor_version 1}
     :display_information
     {:background_color "#006db6"       ; Broad blue
      :description      "Tell us when new files show up in the FTP site."
      :long_description long
      :name             "ClinVar FTP Watcher"},
     #_#_
     :settings
     {:is_hosted              false
      :org_deploy_enabled     false
      :socket_mode_enabled    false
      :token_rotation_enabled false}}))

(defn getenv
  "Throw or return the value of environment VARIABLE in process."
  [variable]
  (let [result (System/getenv variable)]
    (when-not result
      (throw (ex-info (format "Set the %s environment variable" variable) {})))
    result))

(def clinvar-ftp-watcher-topic "clinvar-ftp-watcher")
;; (delay(getenv "CLINVAR_FTP_WATCHER_TOPIC")))

(def jaas-config (getenv "DX_JAAS_CONFIG"))

(def kafka-config {:common {"ssl.endpoint.identification.algorithm" "https"
                            "sasl.mechanism" "PLAIN"
                            "request.timeout.ms" "20000"
                            "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092"
                            "retry.backoff.ms" "500"
                            "security.protocol" "SASL_SSL"
                            "sasl.jaas.config" jaas-config }
                   :consumer {"key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
                              "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"}
                   :producer {"key.serializer" "org.apache.kafka.common.serialization.StringSerializer"
                              "value.serializer" "org.apache.kafka.common.serialization.StringSerializer"}})

(defn client-configuration
  "Create client configuration as properties"
  [config]
  (let [props (new Properties)]
    (doseq [p config]
      (.put props (p 0) (p 1)))
    props))

(defn topic-partitions
  [consumer topic-name]
  (let [partition-infos (.partitionsFor consumer topic-name)]
    (map #(TopicPartition. (.topic %) (.partition %)) partition-infos)))

(defn consumer-for-topic
  "Return consumer for topic"
  [topic-name]
  (let [cluster-config (client-configuration kafka-config)
        consumer-config (merge-with into (:common cluster-config) (:consumer cluster-config))
        consumer (KafkaConsumer. consumer-config)
        topic-list (doto (java.util.ArrayList.)
                     (.add (TopicPartition. topic-name 0)))]
    (.assign consumer topic-list)
    consumer))

(defn producer-record-for [kafka-topic-name key value]
  (ProducerRecord. kafka-topic-name key value))

(def slack-channel
  "Post messages to this Slack channel."
  (delay (getenv "CLINVAR_FTP_WATCHER_SLACK_CHANNEL"))) ;; secrets in clingen-dev secret manager

(def slack-bot-token
  "This is the Slack Bot token."
  (delay (getenv "CLINVAR_FTP_WATCHER_SLACK_BOT_TOKEN"))) ;;; secrets in clingen-dev secret manager

;; More information on the meaning of error responses:
;; https://api.slack.com/methods/chat.postMessage#errors
;;
(defn ^:private post-slack-message
  "Post EDN message to CHANNEL with link unfurling disabled."
  [edn]
  (let [message (format "```%s```" (with-out-str (pprint edn)))
        body    (json/write-str {:channel      @slack-channel
                                 :text         message
                                 :unfurl_links false
                                 :unfurl_media false})
        auth (auth-header @slack-bot-token)]
    (-> {:as      :stream
         :body    body
         :headers (assoc auth "Content-type" "application/json;charset=utf-8")
         :method  :post
         :url     "https://slack.com/api/chat.postMessage"}
        http/request deref :body io/reader parse-json)))

;; Slack API has its own way of reporting statuses:
;; https://api.slack.com/web#slack-web-api__evaluating-responses
;;
(defn ^:private post-slack-message-or-throw
  "Post `message` to `channel` and throw if response indicates a failure."
  [message]
  (let [response (post-slack-message message)]
    (when-not (:ok response)
      (throw (ex-info "Slack API chat.postMessage failed"
                      {:message  message
                       :response response})))
    response))


;; gcloud scheduler jobs doesn't get its --location default from config!
;; This should only be run once, and then when things change
;;
(defn ^:private deploy
  "Deploy this thing."
  [& args]
  (let [project  "clingen-dev"
        region   "us-central1"
        schedule "23 * * * *"
        watcher  "clinvar-ftp-watcher"
        gclouds  [["config" "set" "core/project" project]
                  ["config" "set" "compute/region" region]
                  ["config" "set" "artifacts/location" region]
                  ["pubsub" "topics" "create" watcher]
                  ["pubsub" "subscriptions" "create" watcher "--topic" watcher]
                  ["scheduler" "jobs" "create" "pubsub" watcher
                   "--location" region "--topic" watcher "--schedule" schedule
                   "--message-body" "PING"]
                  ["pubsub" "topics" "publish" watcher "--message" "PING"]]]
    (letfn [(gcloud [args] (apply shell (into ["gcloud"] args)))]
      (map gcloud gclouds))))

(defn get-last-state []
  "Read the last state from the topic"
  (consumer-for-topic clinvar-ftp-watcher-topic))

(defn save-state [state]
  "Write the new state to the topic"
  state)

(defn has-new-files? [state]
  "The number of files in :files is more than zero"
  (> (count (:files state)) 0))

(defn get-new-state []
  "Determine the current state of ftp files on clinvar ftp site relative to the last
   date the state was saved"
  (let [now (Date.)
        ;;last-state (get-last-state)
        last-state {:previous-run-date "2022-12-31"
                    :last-run-date  #inst "2023-01-26"
                    :files nil}
        last-run-date (:last-run-date last-state)
        files (ftp-since last-run-date)]
    {:previous-run-date last-run-date
     :last-run-date  now
     :files files}))

(defn -main
  "Run VERB, perhaps passing it MORE as arguments."
  [& args]
  (let [[verb & other-args] args
        new-state (get-new-state)]
    (when (has-new-files? new-state)
      (save-state new-state))
    (case verb
      "deploy" (apply deploy other-args)
      "slack"  (pprint (post-slack-message-or-throw new-state))
      "test"   (pprint new-state)
      (pprint "help"))
    (System/exit 0)))

(def wtf
  (reify CloudEventsFunction
    (^void accept [this ^CloudEvent cloudevent]
     (dump this)
     (dump cloudevent)
     (let [event (-> cloudevent .getData .toBytes slurp)]
       (-main "slack" event)))))

(gen-class
 :implements   [com.google.cloud.functions.CloudEventsFunction]
 :name         injest.InjestCloudEventsFunction
 :prefix       InjestCloudEventsFunction-)

(defn InjestCloudEventsFunction-accept
  [this ^CloudEvent cloudevent]
  (dump this)
  (dump cloudevent)
  (let [event (-> cloudevent .getData .toBytes slurp)]
    (dump event)
    (-main "slack" event)))


(comment
  "https://cloud.google.com/secret-manager/docs/reference/rpc/google.cloud.secrets.v1beta1#createsecretrequest"
  "clj -M -m injest slack"
  "https://stackoverflow.com/questions/58409161/channel-not-found-error-while-sending-a-message-to-myself"
  "https://github.com/broadinstitute/tgg-terraform-modules/tree/main/scheduled-cloudfunction"
  "gcloud functions runtimes list --project=clingen-dev --region=us-central1"
  "gcloud functions deploy clinvar-ftp-watcher --allow-unauthenticated --region=us-central1 --runtime=java17 --trigger-topic=clinvar-ftp-watcher --source=target --entry-point=sumthang<classname>" ;; move up - move to terraform
  (-main "test")
  (-main "slack")
  tbl)


;; 2023-01-24
;; spoke with LB and decided on the following functionality:
;;     - Don't use the DSP data, instead use a kafka topic to store the last successfully listed FTP files
;;       from the clinvar ftp site.
;;     - Always reference that topic and always use the last entry as the last source of truth to start looking
;;       for newer FTP files.
