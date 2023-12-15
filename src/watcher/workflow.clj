(ns watcher.workflow
  "A place for GCP Workflow related artifacts."
  (:require [watcher.util :refer [getenv]]
            [clojure.data.json :as json])
  (:import [com.google.cloud.workflows.v1 WorkflowName]
           [com.google.cloud.workflows.executions.v1 ExecutionsClient Execution CreateExecutionRequest]))

(def project-id (delay (getenv "GCP_WORKFLOW_PROJECT_ID"))) ;; example "clingen-dev"
(def location (delay (getenv "GCP_WORKFLOW_LOCATION"))) ;; example "us-central1"
(def workflow (delay (getenv "GCP_WORKFLOW_NAME")) ) ;; example "clinvar-ingest"

(comment
  "{\"Name\":\"ClinVarVariationRelease_2023-1209.xml.gz\",\"Size\":3192148421,\"Released\":\"2023-12-10 01:49:23\",\"Last Modified\":\"2023-12-10 01:49:23\",\"Directory\":\"http:\\\\//ftp.ncbi.nlm.nih.gov\\/pub\\/clinvar\\/xml\\/clinvar_variation\\/weekly_release\",\"Release Date\":\"2023-12-09\"}")

;; clojure version of the code from here:
;; https://github.com/GoogleCloudPlatform/java-docs-samples/blob/HEAD/workflows/cloud-client/src/main/java/com/example/workflows/WorkflowsQuickstart.java
;;
(defn initiate-workflow [payload]
  "Pass a ClinVar release payload to initiate the google workflow."
  (let [workflow (WorkflowName/of @project-id @location @workflow)
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

