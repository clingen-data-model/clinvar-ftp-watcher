(ns watcher.deploy
  "Deploys cloud function and related infrastructure."
  (:require [watcher.util      :as util])
  (:import [com.google.cloud.functions CloudEventsFunction]
           [io.cloudevents CloudEvent]))

;; gcloud scheduler jobs doesn't get its --location default from config!
;; This should only be run once, and then when things change
;;
(defn deploy
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
    (letfn [(gcloud [args] (apply util/shell (into ["gcloud"] args)))]
      (map gcloud gclouds))))

;; (def wtf
;;   (reify CloudEventsFunction
;;     (^void accept [this ^CloudEvent cloudevent]
;;      (util/dump this)
;;      (util/dump cloudevent)
;;      (let [event (-> cloudevent .getData .toBytes slurp)]
;;        (-main "slack" event)))))

;; (gen-class
;;  :implements   [com.google.cloud.functions.CloudEventsFunction]
;;  :name         injest.InjestCloudEventsFunction
;;  :prefix       InjestCloudEventsFunction-)

;; (defn InjestCloudEventsFunction-accept
;;   [this ^CloudEvent cloudevent]
;;   (util/dump this)
;;   (util/dump cloudevent)
;;   (let [event (-> cloudevent .getData .toBytes slurp)]
;;     (util/dump event)
;;     (-main "slack" event)))

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
