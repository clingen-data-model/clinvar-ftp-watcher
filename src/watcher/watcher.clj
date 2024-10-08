(ns watcher.watcher
  "Source for cloud function that periodicly watches the clivar ftp dir
  for new files. State is maintained in a kafka topic."
  (:require [watcher.ftpparse     :as ftpparse]
            [watcher.slack        :refer [post-slack-message-or-throw]]
            [watcher.stream       :as stream]
            [watcher.util         :as util]
            [watcher.cloudrunjob  :as job]
            [clojure.data.json    :as json]
            [clojure.instant      :refer [read-instant-date]]
            [clojure.pprint       :refer [pprint]]
            [taoensso.timbre      :refer [log info warn error]])
  (:import [java.util Date])
  (:gen-class))

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
;;                                                                  "Last Modified" "2023-01-16 11:48:46"}])} 
;;
(defn get-latest-files-since
  "Determine the current state of ftp files on clinvar ftp site relative
  to the last time we processed."
  [^java.util.Date instant]
  (let [ftp-files-since-last (ftpparse/ftp-since instant)]
      (when (> (count ftp-files-since-last) 0)
        {:since-date instant
         :files ftp-files-since-last})))

;; TODO - this needs a test multiple entries in collection vs single
(defn get-last-processed-date
  "Get the 'Last Modified' date from the files processed collection."
  [last-processed-files]
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
  and writes the newer files to the clinvar-ftp-watcher topic and initiates the google cloud
  run job for each found file.
  Args:
   --kafka     - will not write newly found files to the kafka stream
   --job       - will not initiate the google cloud job to process the files
  "
  [& args]
  (let [write-to-kafka (= -1 (if (some? args) (.indexOf args "--kafka") -1))
        initiate-job (= -1 (if (some? args) (.indexOf args "--job") -1))
        files  (-> (stream/get-last-processed)
                   get-last-processed-date
                   get-latest-files-since)
        file-details (process-file-details files)
        date-processed (str (Date.))
        workflow-job-name (job/gcp-job-name)
        message (str "FTP Watcher run on " date-processed " found " (count (:files files))
                     " files to be processed by " workflow-job-name ".")]
    (info message)
    (when (new-files? files)
      (if write-to-kafka
        (do
          (stream/save-to-topic date-processed (json/write-str file-details))
          (info "Updated kafka topic with new file details."))
        (info "No new file information written to kafka."))
      (if initiate-job
        (do
          (post-slack-message-or-throw message)
          (doseq [release-map file-details]
            (try
              ;; Dereferencing this future will cause this process to wait for future completion.
              (let [initiated-job (future (job/initiate-cloud-run-job release-map))]
                (info "Initiated cloud run job " workflow-job-name " with payload " release-map))
              (catch Throwable t
                (let [message (str "Error invoking " workflow-job-name " with payload " release-map ".")]
                  (post-slack-message-or-throw message))))))
        (info "Cloud run job not initiated.")))))


(comment
  (let [file-details (process-file-details  (get-latest-files-since #inst "2024-06-01"))]
    (doseq [release file-details]
      ((workflow/initiate-workflow (json/write-str release))))))


(comment
  (-> (ftpparse/ftp-since #inst "2024-07-01")))

(comment
  (-> (stream/get-last-processed)
                  get-last-processed-date
                  get-latest-files-since
                  ))
