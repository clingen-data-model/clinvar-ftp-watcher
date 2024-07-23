(ns watcher.ftpparse
  "FTP site parsing."
  (:require [watcher.util]
            [clojure.data.json  :as json]
            [clojure.instant    :as instant]
            [clojure.java.io    :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint     :refer [pprint]]
            [clojure.spec.alpha :as s]
            [clojure.string     :as str]
            [hickory.core       :as html]
            [hickory.select     :as css]
            [org.httpkit.client :as http])
  (:import [java.text SimpleDateFormat]))

;; Sensible defults
(def DEFAULT_NCBI_FTP_URL "https://ftp.ncbi.nlm.nih.gov")
(def DEFAULT_CLINVAR_WEEKLY_DIR "/pub/clinvar/xml/VCV_xml_old_format/weekly_release")
(def DEFAULT_FILE_NAME_BASE "ClinVarVariationRelease")

(s/def ::table
  (s/and sequential?
         (partial every? sequential?)
         #(== 1 (count (set (map count %))))
         #(== (count (set (first %))) (count (first %)))))

(defn ftp-site []
  "FTP site of the National Library of Medicine. If no value is defined in the environment
   for 'NCBI_CLINVAR_FTP_SITE' defaults to DEFAULT_NCBI_FTP_URL"
  (let [ncbi-ftp (System/getenv "NCBI_CLINVAR_FTP_SITE")]
    (if (nil? ncbi-ftp)
      DEFAULT_NCBI_FTP_URL
      ncbi-ftp)))

(defn ftp-file-name-base []
  "FTP file base name. If no value is defined in the environment
   for 'NCBI_CLINVAR_FILE_NAME_BASE' defaults to DEFAULT_FILE_NAME_BASE"
  (let [ncbi-file-name-base (System/getenv "NCBI_CLINVAR_FILE_NAME_BASE")]
    (if (nil? ncbi-file-name-base)
      DEFAULT_FILE_NAME_BASE
      ncbi-file-name-base)))

;; The FTP/HTTP server requires a final / on URLs.
;;
(defn weekly-ftp-dir []
  "NCBI's ClinVar weekly release directory. if no value is defined in the environment
   for 'NCBI_CLINVAR_WEEKLY_FTP_DIR' defaults to DEFAULT_CLINVAR_WEEKLY_DIR"
  (let [weekly-dir (System/getenv "NCBI_CLINVAR_WEEKLY_FTP_DIR")]
    (if (nil? weekly-dir)
      DEFAULT_CLINVAR_WEEKLY_DIR
      weekly-dir)))

(def weekly-ftp-url
  "The weekly_release ftp URL."
  (str (ftp-site) (weekly-ftp-dir)))

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

(def ftp-time
  "This is how the FTP site timestamps."
  (SimpleDateFormat. "yyyy-MM-dd kk:mm:ss"))

(def ftp-time-ymdhm
  "And sometimes THIS is how the FTP site timestamps."
  (SimpleDateFormat. "yyyy-MM-dd kk:mm"))

(def ftp-time-ymd
  "And sometimes THIS is how the FTP site timestamps."
  (SimpleDateFormat. "yyyy-MM-dd"))

(defn instify
  "Parse string S as a date and return its Instant or NIL."
  [s]
  (try (.parse ftp-time s) (catch Throwable _)))

(defn instify-ymdhm
  "Parse string S as a date differently and return its Instant or NIL."
  [s]
  (try (.parse ftp-time-ymdhm s) (catch Throwable _)))

(defn ^:private longify
  "Return S or S parsed into a Long after stripping commas."
  [s]
  (or (try (-> s (str/replace "," "") parse-long)
           (catch Throwable _))
      s))

(defn extract-date-from-file
  "Extract the date from ClinVarVariationRelease_2020-0602.xml.gz file as 2020-06-02"
  [file]
  (let [result (re-matches (re-pattern (str (ftp-file-name-base) "_(\\d\\d\\d\\d)-(\\d\\d)(\\d\\d).xml.gz")) file)]
    (str/join "-" (rest result))))

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

(defn ftp-since
  "Return files from currently on the ncbi weekly url site
  more recent than instant in a vector."
  [^java.util.Date instant]
  (letfn [(since? [file]
            (apply < (map inst-ms [instant (file "Last Modified")])))
          (filename? [file]
            (let [regexp (re-pattern (str (ftp-file-name-base) "_\\d\\d\\d\\d-\\d\\d\\d\\d.xml.gz"))]
              (some? (re-matches regexp (file "Name")))))]
    (->> weekly-ftp-url fetch-ftp parse-ftp rest (filter (every-pred since? filename?)) vec)))

(comment
  (ftp-since #inst "2024-07-01"))

