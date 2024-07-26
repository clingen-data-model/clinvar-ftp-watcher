(ns watcher.cloudrunjob
  "A place for GCP Cloud Run Job related artifacts."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :refer [log info warn error]])
  (:import  [com.google.cloud.run.v2 JobsClient Execution JobName EnvVar
             RunJobRequest RunJobRequest$Overrides RunJobRequest$Overrides$ContainerOverride]))


(def DEFAULT_GCP_PROJECT_ID "clingen-dev")
(def DEFAULT_GCP_WORKFLOW_LOCATION "us-east1") 
(def DEFAULT_GCP_WORKFLOW_NAME "clinvar-vcv-ingest")

;; TODO s/b a macro?
(defn gcp-project-id []
  (let [project-id (System/getenv "GCP_PROJECT_ID")]
    (if (nil? project-id)
      DEFAULT_GCP_PROJECT_ID
      project-id)))

(defn gcp-location []
  (let [location (System/getenv "GCP_WORKFLOW_LOCATION")]
    (if (nil? location)
      DEFAULT_GCP_WORKFLOW_LOCATION
      location)))

(defn gcp-job-name []
  (let [job-name (System/getenv "GCP_WORKFLOW_NAME")]
    (if (nil? job-name)
      DEFAULT_GCP_WORKFLOW_NAME
      job-name)))


(defn normalize-keys [^clojure.lang.IPersistentMap payload]
  "Normalize the keys in payload map to be lower case and replace spaces with underscore"
  (reduce (fn [m [k v]]
            (assoc m (-> k
                         str/lower-case
                         (str/replace #" " "_"))
                   v))
          {}
          payload))


(defn envvar [k v]
  "Create EnvVars to use to pass as overrides to cloud run job. Note that all values are strings."
  (-> (EnvVar/newBuilder)
      (.setName k)
      (.setValue (if (= "java.lang.String" (type v)) v (str v)))
      .build))


(defn envars [^clojure.lang.IPersistentMap payload]
  "Creates normalized EnvVars for each argument in the payload map."
  (reduce (fn [l [k v]]
            (conj l (envvar k v)))
          []
          (normalize-keys payload)))


(defn overrides [^clojure.lang.IPersistentMap payload]
  "Create and populate the RunJobRequest with environment variable overrides."
  (-> (RunJobRequest$Overrides/newBuilder)
      (.addContainerOverrides (-> (RunJobRequest$Overrides$ContainerOverride/newBuilder)
                                  (.addAllEnv (envars payload))
                                  .build))
      .build))

(defn initiate-cloud-run-job [^clojure.lang.IPersistentMap payload]
  "Pass a ClinVar release payload map to initiate the google cloud run job to ingest."
  (let [jobs-client (JobsClient/create)]
    (try
      (let [job-name (-> (JobName/of (gcp-project-id) (gcp-location) (gcp-job-name))
                         .toString)
            overrides (overrides payload)
            run-job-request (-> (RunJobRequest/newBuilder)
                                (.setName job-name)
                                (.setOverrides overrides)
                                .build)]
        (.runJobAsync jobs-client run-job-request))
      (catch Exception e (throw e))
      (finally (.close jobs-client)))))


#_ (initiate-cloud-run-job 
   {"Name" "ClinVarVariationRelease_2023-1209.xml.gz", 
    "Size" 3192148421,
    "Released" "2023-12-10 01:49:23",
    "Last Modified" "2023-12-10 01:49:23",
    "Directory" "http://ftp.ncbi.nlm.nih.gov/pub/clinvar/xml/clinvar_variation/weekly_release",
    "Release Date" "2023-12-09"})

