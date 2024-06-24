(ns watcher.workflow
  "A place for GCP Workflow related artifacts."
  (:require [clojure.data.json :as json])
  (:import [com.google.cloud.workflows.v1 WorkflowName]
           [com.google.cloud.workflows.executions.v1 ExecutionsClient Execution CreateExecutionRequest]))


(def DEFAULT_GCP_WORKFLOW_PROJECT_ID "clingen-dev")
(def DEFAULT_GCP_WORKFLOW_LOCATION "us-east1") 
(def DEFAULT_GCP_WORKFLOW_NAME "clinvar-vcv-ingest")

;; TODO s/b a macro?
(defn gcp-workflow-project-id []
  (let [workflow-project-id (System/getenv "GCP_WORKFLOW_PROJECT_ID")]
    (if (nil? workflow-project-id)
      DEFAULT_GCP_WORKFLOW_PROJECT_ID
      workflow-project-id)))

(defn gcp-workflow-location []
  (let [workflow-location (System/getenv "GCP_WORKFLOW_LOCATION")]
    (if (nil? workflow-location)
      DEFAULT_GCP_WORKFLOW_LOCATION
      workflow-location)))

(defn gcp-workflow-name []
  (let [workflow-name (System/getenv "GCP_WORKFLOW_NAME")]
    (if (nil? workflow-name)
      DEFAULT_GCP_WORKFLOW_NAME
      workflow-name)))

(comment
  "{\"Name\":\"ClinVarVariationRelease_2023-1209.xml.gz\",\"Size\":3192148421,\"Released\":\"2023-12-10 01:49:23\",\"Last Modified\":\"2023-12-10 01:49:23\",\"Directory\":\"http:\\\\//ftp.ncbi.nlm.nih.gov\\/pub\\/clinvar\\/xml\\/clinvar_variation\\/weekly_release\",\"Release Date\":\"2023-12-09\"}")

;; clojure version of the code from here:
;; https://github.com/GoogleCloudPlatform/java-docs-samples/blob/HEAD/workflows/cloud-client/src/main/java/com/example/workflows/WorkflowsQuickstart.java
;;
(defn initiate-workflow [payload]
  "Pass a ClinVar release payload to initiate the google workflow."
  (let [workflow (WorkflowName/of (gcp-workflow-project-id) (gcp-workflow-location) (gcp-workflow-name))
        execution (-> (Execution/newBuilder)
                      (.setArgument payload)
                      .build)
        request (-> (CreateExecutionRequest/newBuilder)
                    (.setParent (.toString workflow))
                    (.setExecution execution)
                    .build)
        client (ExecutionsClient/create)
        response (.createExecution client request)
        execution-name (.getName response)]
    execution-name))

