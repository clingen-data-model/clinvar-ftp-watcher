(ns watcher.stream
  "A place for kafka stream related things."
  (:require [watcher.ftpparse  :as ftpparse]
            [watcher.util      :as util]
            [clojure.data.json :as json])
  (:import [java.util ArrayList Properties]
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecord ConsumerRecords OffsetAndMetadata]
           [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.common PartitionInfo TopicPartition]))

;; Sensible defaults
(def DEFAULT_FTP_WATCHER_TOPIC "clinvar_ftp_watcher")

(defn clinvar-ftp-watcher-topic []
  "The topic where the results of new files found on the NCBI Clinvar FTP site will be saved.
   If 'CLINVAR_FTP_WATCHER_TOPIC' is not defined in the environment, this defaults to
   'clinvar_ftp_watcher'"
  (let [watcher-topic (System/getenv "CLINVAR_FTP_WATCHER_TOPIC")]
    (if (nil? watcher-topic)
      DEFAULT_FTP_WATCHER_TOPIC
      watcher-topic)))

(def kafka-config (delay {:common {"ssl.endpoint.identification.algorithm" "https"
                             "sasl.mechanism" "PLAIN"
                             "request.timeout.ms" "20000"
                             "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092"
                             "retry.backoff.ms" "500"
                             "security.protocol" "SASL_SSL"
                             "sasl.jaas.config" (util/getenv "DX_JAAS_CONFIG")}
                    :consumer {"key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
                               "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"}
                    :producer {"key.serializer" "org.apache.kafka.common.serialization.StringSerializer"
                               "value.serializer" "org.apache.kafka.common.serialization.StringSerializer"}}))

(defn client-configuration
  "Create client configuration as properties"
  [config]
  (let [props (new Properties)]
    (doseq [p config]
      (.put props (p 0) (p 1)))
    props))

(defn topic-partitions
  "Return the kafka PartitionInfo records for the topic"
  [consumer topic]
  (let [partition-infos (.partitionsFor consumer topic)]
    (map #(TopicPartition. (.topic %) (.partition %)) partition-infos)))

(defn topic-consumer
  "Return consumer for topic"
  [topic-name]
  (let [cluster-config (client-configuration @kafka-config)
        consumer-config (merge-with into (:common cluster-config) (:consumer cluster-config))]
    (KafkaConsumer. consumer-config)))

(defn topic-producer
  "Return a producer"
  []
  (let [cluster-config (client-configuration @kafka-config)
        producer-config (merge-with into (:common cluster-config) (:producer cluster-config))]
    (KafkaProducer. producer-config))) 

;; TODO - Re-evaluate if this is current - especially the ncbi ftp host should be here?
;; sample kafka topic message:
;;  "key": "Mon Jan 08 01:45:40 UTC 2024",
;;  "payload":"[{\"Name\":\"ClinVarVariationRelease_2024-0107.xml.gz\",\"Size\":3294923883,\"Released\":\"2024-01-07 20:04:48\",\"Last Modified\":\"2024-01-07 20:04:48\",\"Directory\":\"\\/pub\\/clinvar\\/xml\\/clinvar_variation\\/weekly_release\",\"Release Date\":\"2024-01-07\"}]
;;
(defn get-last-processed
  "Retrieve the last processed message of the kafka topic.
   Returns a dictionary containing the date of the entry and an array of files that
   were found. Generally, there will be only one file, but there may be multiple files
  if there was a problem with ncbi releases."
  []
  (with-open [consumer (topic-consumer (clinvar-ftp-watcher-topic))]
    (let [topic-partitions (topic-partitions consumer (clinvar-ftp-watcher-topic))
          _ (.assign consumer topic-partitions)
          [topicPartition, endOffset] (-> (.endOffsets consumer topic-partitions) first)]
      (when (> endOffset 0)
        (.seek consumer (first topic-partitions) (dec endOffset))
          (let [consumerRecord (-> (.poll consumer 1000) last)
                date (.key consumerRecord)
                files (.value consumerRecord)]
            {date files})))))

(defn save-to-topic
  "Save the key and payload to the kafka topic."
  [key payload]
  (with-open [producer (topic-producer)]
    (let [producer-record (ProducerRecord. (clinvar-ftp-watcher-topic) key payload)]
      (.send producer producer-record))))

(comment
  (get-last-processed)
  (save-to-topic (str (Date.)) (json/write-str [{"Name" "ClinVarVariationRelease_2023-0107.xml.gz",
                                        "Size" 2287039835,
                                        "Released" "2023-01-09 09:23:44",
                                        "Last Modified" "2023-01-09 09:23:44"
                                        "Directory" ftpparse/weekly-ftp-url
                                        "Release Date" (ftpparse/extract-date-from-file "ClinVarVariationRelease_2023-0107.xml.gz")
                                        }])))

