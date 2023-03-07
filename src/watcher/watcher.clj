(ns watcher.watcher
  "Source for cloud function that periodicly watches the clivar ftp dir
  for new files. State is maintained in a kafka topic."
  (:require [watcher.ftpparse   :as ftpparse]
            [watcher.stream     :as stream]
            [watcher.util       :as util]
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

;;last-processed {"2023-02-02T16:55:01.189-00:00" (json/write-str [{"Name" "ClinVarVariationRelease_2023-0107.xml.gz",
;;                                                                  "Size" 2287039835,
;;                                                                  "Released" "2023-01-09 09:23:44",
;;                                                                  "Last Modified" "2023-01-09 09:23:44"}
;;                                                                 {"Name" "ClinVarVariationRelease_2023-0115.xml.gz",
;;                                                                  "Size" 2286049499,
;;                                                                  "Released" "2023-01-16 11:48:46",
;;                                                                 "Last Modified" "2023-01-16 11:48:46"} 
;;
(defn get-latest-files
  "Determine the current state of ftp files on clinvar ftp site relative
  to the last time we processed."
  []
  (let [last-processed (stream/get-last-processed)]
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
          ftp-files-since-last (ftpparse/ftp-since last-file-date)]
      (when (> (count ftp-files-since-last) 0)
        {:since-date (-> last-processed
                         last
                         key)
         :files ftp-files-since-last}))))

(defn process-file-details
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
  "Main processing point: reads the last message from the clinvar-ftp-watcher topic,
  gets the last dated file, reads the clinvar ftp site looking for files with newer dates,
  and writes the newer files to the clinvar-ftp-watcher topic."
  [& args]
  (let [files (get-latest-files)
        date-processed (str (Date.))]
    (println (str "Run on " date-processed " processed " (count files) " files."))
    (when (new-files? files)
      (stream/save date-processed (json/write-str (process-file-details files))))))

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
