(ns watcher.deploy
  "Deploys cloud function and related infrastructure."
  (:require [watcher.util      :as util]))


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


(comment
  "https://cloud.google.com/secret-manager/docs/reference/rpc/google.cloud.secrets.v1beta1#createsecretrequest"
  "clj -M -m injest slack"
  "https://stackoverflow.com/questions/58409161/channel-not-found-error-while-sending-a-message-to-myself"
  "https://github.com/broadinstitute/tgg-terraform-modules/tree/main/scheduled-cloudfunction"
  "gcloud functions runtimes list --project=clingen-dev --region=us-central1"
  "gcloud functions deploy clinvar-ftp-watcher --allow-unauthenticated --region=us-central1 --runtime=java17 --trigger-topic=clinvar-ftp-watcher --source=target --entry-point=sumthang<classname>" ;; move up - move to terraform
  "https://cloud.google.com/functions/docs/writing/write-event-driven-functions"
 )
