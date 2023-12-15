(ns watcher.watcher
  "Source for cloud function that periodicly watches the clivar ftp dir
  for new files. State is maintained in a kafka topic."
  (:require [watcher.ftpparse   :as ftpparse]
            [watcher.stream     :as stream]
            [watcher.util       :as util]
            [watcher.workflow   :as workflow]
            [clojure.data.json  :as json]
            [clojure.instant    :refer [read-instant-date]]
            [clojure.pprint     :refer [pprint]]
            [taoensso.timbre    :refer [log info warn error]])
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

;;last-processed {"2023-02-02T16:55:01.189-00:00" (json/write-str [{"Name" "ClinVarVariationRelease_2023-0107.xml.gz",
;;                                                                  "Size" 2287039835,
;;                                                                  "Released" "2023-01-09 09:23:44",
;;                                                                  "Last Modified" "2023-01-09 09:23:44"}
;;                                                                 {"Name" "ClinVarVariationRelease_2023-0115.xml.gz",
;;                                                                  "Size" 2286049499,
;;                                                                  "Released" "2023-01-16 11:48:46",
;;                                                                 "Last Modified" "2023-01-16 11:48:46"}])} 
;;
(defn get-latest-files-since
  "Determine the current state of ftp files on clinvar ftp site relative
  to the last time we processed."
  [last-file-date]
  (let [ftp-files-since-last (ftpparse/ftp-since last-file-date)]
      (when (> (count ftp-files-since-last) 0)
        {:since-date last-file-date
         :files ftp-files-since-last})))

(defn get-last-processed-date [last-processed-files]
  (-> last-processed-files
      last
      val
      json/read-str
      last
      (get "Last Modified")
      ftpparse/instify))

(defn process-file-details
  "Create a map of information on each of the new files reported
  from the clinvar ftp site"
  [current]
  (reduce (fn [vec entry]
            (conj vec { "Name" (entry "Name")
                       "Size" (entry "Size")
                       "Released" (.format ftpparse/ftp-time (entry "Released"))
                       "Last Modified" (.format ftpparse/ftp-time (entry "Last Modified"))
                       "Directory" (ftpparse/weekly-ftp-dir)
                       "Host" (ftpparse/ftp-site)
                       "Release Date" (ftpparse/extract-date-from-file (entry "Name"))}))
          []
          (:files current)))

(defn -main
  "Main processing point: reads the last message from the clinvar-ftp-watcher topic,
  gets the last dated file, reads the clinvar ftp site looking for files with newer dates,
  and writes the newer files to the clinvar-ftp-watcher topic and initiates the google workflow
  for each found file."
  [& args]
  (let [last-processed-files (stream/get-last-processed)
        last-processed-date (get-last-processed-date last-processed-files)
        files (get-latest-files-since last-processed-date)
        file-details (process-file-details files)
        date-processed (str (Date.))]
    (info (str "Run on " date-processed " processed " (count files) " files."))
    (when (new-files? files)
      (if (< (.indexOf args "--dont-write-to-kafka") 0)
        (do
          (stream/save date-processed (json/write-str file-details))
          (info "Updated kafka topic."))
        (info "No information written to kafka."))
      (if (< (.indexOf args "--dont-initiate-workflow") 0)
        (doseq [release file-details]
          (let [payload (json/write-str release)
                initiated-workflow (workflow/initiate-workflow payload)]
            (info "Initiated workflow " initiated-workflow " with payload " payload)))
        (info "No workflows initiated.")))))


(defn foo []
  (let [file-details (process-file-details  (get-latest-files-since #inst "2023-01-01"))]
    (doseq [release file-details]
      ((workflow/initiate-workflow (json/write-str release))))))

(comment
  "https://cloud.google.com/secret-manager/docs/reference/rpc/google.cloud.secrets.v1beta1#createsecretrequest"
  "clj -M -m injest slack"
  "https://stackoverflow.com/questions/58409161/channel-not-found-error-while-sending-a-message-to-myself"
  "https://cloud.google.com/functions/docs/writing/write-event-driven-functions"
  "https://github.com/broadinstitute/tgg-terraform-modules/tree/main/scheduled-cloudfunction"
  "gcloud functions runtimes list --project=clingen-dev --region=us-central1"
  "gcloud functions deploy clinvar-ftp-watcher --allow-unauthenticated --region=us-central1 --runtime=java17 --trigger-topic=clinvar-ftp-watcher --source=target --entry-point=" ;; move up - move to terraform
)

(comment
  (-> (ftpparse/ftp-since #inst "2023-01-01")))
