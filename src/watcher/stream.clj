(ns watcher.stream
  "A place for kafka stream related things."
  (:require [watcher.util      :as util]
            [clojure.data.json :as json])
  (:import [java.util ArrayList Properties]
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecord ConsumerRecords OffsetAndMetadata]
           [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.common PartitionInfo TopicPartition]))

(def clinvar-ftp-watcher-topic "clinvar_ftp_watcher")

(def kafka-config {:common {"ssl.endpoint.identification.algorithm" "https"
                            "sasl.mechanism" "PLAIN"
                            "request.timeout.ms" "20000"
                            "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092"
                            "retry.backoff.ms" "500"
                            "security.protocol" "SASL_SSL"
                            "sasl.jaas.config" "org.apache.kafka.common.security.plain.PlainLoginModule required username='IJVUJZI3NM2CUYDT' password='nf4M8VLWPXuIbXRhaqDlTK0wphzLxxuN99wQ5UE9uxgHVK7q8h6kRo0q2HLKdnBq';" }
                   :consumer {"key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
                              "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"}
                   :producer {"key.serializer" "org.apache.kafka.common.serialization.StringSerializer"
                              "value.serializer" "org.apache.kafka.common.serialization.StringSerializer"}})

(defn create-clinvar-history
  []
  )

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
  (let [cluster-config (client-configuration kafka-config)
        consumer-config (merge-with into (:common cluster-config) (:consumer cluster-config))]
    (KafkaConsumer. consumer-config)))

(defn topic-producer
  "Return a producer"
  []
  (let [cluster-config (client-configuration kafka-config)
        producer-config (merge-with into (:common cluster-config) (:producer cluster-config))]
    (KafkaProducer. producer-config))) 
    
(defn get-last-processed
  "Retrieve the last processed message of the kafka topic"
  []
  (with-open [consumer (topic-consumer clinvar-ftp-watcher-topic)]
    (let [topic-partitions (topic-partitions consumer clinvar-ftp-watcher-topic)
          _ (.assign consumer topic-partitions)
          [topicPartition, endOffset] (-> (.endOffsets consumer topic-partitions) first)]
      (when (> endOffset 0)
          (.seek consumer (first topic-partitions) (dec endOffset))
          (let [consumerRecord (-> (.poll consumer 1000) last)
                date (.key consumerRecord)
                files (.value consumerRecord)]
            {date files})))))

(defn save
  "Save the key and value to the kafka topic"
  [key value]
  (with-open [producer (topic-producer)]
    (let [producer-record (ProducerRecord. clinvar-ftp-watcher-topic key value)]
      (.send producer producer-record))))

(comment
  (get-last-processed)
  (save (str (Date.)) (json/write-str [{"Name" "ClinVarVariationRelease_2023-0107.xml.gz",
                                        "Size" 2287039835,
                                        "Released" "2023-01-09 09:23:44",
                                        "Last Modified" "2023-01-09 09:23:44"
                                        "Directory" ftpparse/weekly-ftp-dir
                                        "Release Date" (ftpparse/extract-date-from-file "ClinVarVariationRelease_2023-0107.xml.gz")
                                        }])))

