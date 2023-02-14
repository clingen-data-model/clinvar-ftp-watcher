(ns watcher.watcher
  "Source for cloud function that periodicly watches the clivar ftp dir
  for new files. State is maintained in a kafka topic."
  (:require [watcher.deploy     :as deploy]
            [watcher.ftpparse   :as ftpparse]
            [watcher.stream     :as stream]
            [watcher.slack      :as slack]
            [clojure.data.json  :as json]
            [clojure.instant    :refer [read-instant-date]]
            [clojure.pprint     :refer [pprint]])
  (:import [java.util Date])
  (:gen-class))

(defn get-last-processed
  "Read the last state from the topic"
  []
  (stream/get-last-processed))

(defn new-files?
  "The number of files in :files is more than zero"
  [state]
  (> (count (:files state)) 0))


(defn get-latest-files
  "Determine the current state of ftp files on clinvar ftp site relative
  to the last time we processed."
  []
  (let [last-processed (stream/get-last-processed)]
        ;;last-processed {"2023-02-02T16:55:01.189-00:00" (json/write-str [{"Name" "ClinVarVariationRelease_2023-0107.xml.gz",
        ;;                                                                  "Size" 2287039835,
        ;;                                                                  "Released" "2023-01-09 09:23:44",
        ;;                                                                  "Last Modified" "2023-01-09 09:23:44"}
        ;;                                                                 {"Name" "ClinVarVariationRelease_2023-0115.xml.gz",
        ;;                                                                  "Size" 2286049499,
        ;;                                                                  "Released" "2023-01-16 11:48:46",
        ;;                                                                 "Last Modified" "2023-01-16 11:48:46"}          ;;                                                             ])
        ;;                }]
    (assert (some? last-processed) (str "No data available on '"
                                        stream/clinvar-ftp-watcher-topic
                                        "' kafka topic."))
    (let [last-file-date (-> last-processed
                             last
                             val
                             json/read-str
                             last
                             (get "Last Modified")
                             ftpparse/instify)
          ftp-files (ftpparse/ftp-since last-file-date)]
      {:run-date (-> last-processed
                     last
                     key)
       :files ftp-files})))

;;(->> d (mapv (fn [e] (update e "Released" #(.format ftpparse/ftp-time %))
;;               (mapv (fn [e] (update e "Last Modified" #(.format ftpparse/ftp-time %)))))))

(defn process-files
  "Create a map of information on each of the new files reported
  from the clinvar ftp site"
  [current]
  (reduce (fn [vec entry]
            (conj vec { "Name" (entry "Name")
                       "Size" (entry "Size")
                       "Released" (.format ftpparse/ftp-time (entry "Released"))
                       "Last Modified" (.format ftpparse/ftp-time (entry "Last Modified"))
                       "Directory" ftpparse/weekly-ftp-dir
                       "Release Date" (ftpparse/extract-date-from-file (entry "Name"))}))
          []
          (:files current)))

(defn -main
  "Run VERB, perhaps passing it MORE as arguments."
  [& args]
  (let [new-state (get-latest-files)]
    (when (new-files? new-state)
      (stream/save (str (Date.)) (json/write-str (process-files new-state))))))

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
