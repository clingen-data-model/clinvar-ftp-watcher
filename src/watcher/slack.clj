(ns watcher.slack
  "Communicate via slack."
  (:require [watcher.ftpparse   :as ftpparse]
            [watcher.util       :as util]
            [clojure.data.json  :as json]
            [clojure.java.io    :as io]
            [clojure.pprint     :refer [pprint]]
            [clojure.string     :as str]
            [org.httpkit.client :as http]))

(def ^:private slack-manifest ;; new style slack api - doesn't work
  "The Slack Application manifiest for ClinVar FTP Watcher."
  (let [long (str/join \space ["Notify the genegraph-dev team"
                               "when new files show up in the"
                               ftpparse/weekly-ftp-url
                               "FTP site"
                               "before the Monster Ingest team notices."])]
    {:_metadata
     {:major_version 1
      :minor_version 1}
     :display_information
     {:background_color "#006db6"       ; Broad blue
      :description      "Tell us when new files show up in the FTP site."
      :long_description long
      :name             "ClinVar FTP Watcher"},
     #_#_
     :settings
     {:is_hosted              false
      :org_deploy_enabled     false
      :socket_mode_enabled    false
      :token_rotation_enabled false}}))

(def slack-channel
  "Post messages to this Slack channel."
  (delay (util/getenv "CLINVAR_FTP_WATCHER_SLACK_CHANNEL"))) ;; secrets in clingen-dev secret manager

(def slack-bot-token
  "This is the Slack Bot token."
  (delay (util/getenv "CLINVAR_FTP_WATCHER_SLACK_BOT_TOKEN"))) ;;; secrets in clingen-dev secret manager

;; More information on the meaning of error responses:
;; https://api.slack.com/methods/chat.postMessage#errors
;;
(defn post-slack-message
  "Post EDN message to CHANNEL with link unfurling disabled."
  [edn]
  (let [message (format "```%s```" (with-out-str (pprint edn)))
        body    (json/write-str {:channel      @slack-channel
                                 :text         message
                                 :unfurl_links false
                                 :unfurl_media false})
        auth (util/auth-header @slack-bot-token)]
    (-> {:as      :stream
         :body    body
         :headers (assoc auth "Content-type" "application/json;charset=utf-8")
         :method  :post
         :url     "https://slack.com/api/chat.postMessage"}
        http/request deref :body io/reader util/parse-json)))

;; Slack API has its own way of reporting statuses:
;; https://api.slack.com/web#slack-web-api__evaluating-responses
;;
(defn post-slack-message-or-throw
  "Post `message` to `channel` and throw if response indicates a failure."
  [message]
  (let [response (post-slack-message message)]
    (when-not (:ok response)
      (throw (ex-info "Slack API chat.postMessage failed"
                      {:message  message
                       :response response})))
    response))
