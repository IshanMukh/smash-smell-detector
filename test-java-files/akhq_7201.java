package org.akhq.utils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.SneakyThrows;
import org.akhq.configs.DataMasking;
import org.akhq.configs.JsonMaskingFilter;
import org.akhq.models.Record;
import java.util.List;
import java.util.Map;
@Singleton
@Requires(property = "akhq.security.data-masking.mode", value = "json_mask_by_default")
public class JsonMaskByDefaultMasker implements Masker {
    private final List<JsonMaskingFilter> jsonMaskingFilters;
    private final String jsonMaskReplacement;
    public JsonMaskByDefaultMasker(DataMasking dataMasking) {
        this.jsonMaskingFilters = dataMasking.getJsonFilters();
        this.jsonMaskReplacement = dataMasking.getJsonMaskReplacement();
    }
    public Record maskRecord(Record record) {
        try {
            if(isJson(record)) {
                return jsonMaskingFilters
                    .stream()
                    .filter(jsonMaskingFilter -> record.getTopic().getName().equalsIgnoreCase(jsonMaskingFilter.getTopic()))
                    .findFirst()
                    .map(filter -> applyMasking(record, filter.getKeys()))
                    .orElseGet(() -> applyMasking(record, List.of()));
            } else {
                record.setValue("This record is unable to be masked as it is not a structured object. This record is unavailable to view due to safety measures from json_mask_by_default to not leak sensitive data. Please contact akhq administrator.");
            }
        } catch (Exception e) {
            LOG.error("Error masking record at topic {}, partition {}, offset {} due to {}", record.getTopic(), record.getPartition(), record.getOffset(), e.getMessage());
            record.setValue("An exception occurred during an attempt to mask this record. This record is unavailable to view due to safety measures from json_mask_by_default to not leak sensitive data. Please contact akhq administrator.");
        }
        return record;
    }
    @SneakyThrows
    private Record applyMasking(Record record, List<String> keys) {
        JsonObject jsonElement = JsonParser.parseString(record.getValue()).getAsJsonObject();
        maskAllExcept(jsonElement, keys);
        record.setValue(jsonElement.toString());
        return record;
    }
    private void maskAllExcept(JsonObject jsonElement, List<String> keys) {
        maskAllExcept("",  jsonElement, keys);
    }
    private void maskAllExcept(String currentKey, JsonObject node, List<String> keys) {
        if (node.isJsonObject()) {
            JsonObject objectNode = node.getAsJsonObject();
            for(Map.Entry<String, JsonElement> entry : objectNode.entrySet()) {
                if(entry.getValue().isJsonObject()) {
                    maskAllExcept(currentKey + entry.getKey() + ".", entry.getValue().getAsJsonObject(), keys);
                } else {
                    if(!keys.contains(currentKey + entry.getKey())) {
                        objectNode.addProperty(entry.getKey(), jsonMaskReplacement);
                    }
                }
            }
        }
    }
}
package org.akhq.utils;
import org.akhq.models.Record;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public interface Masker {
    Logger LOG = LoggerFactory.getLogger(Masker.class);
    Record maskRecord(Record record);
    default boolean isJson(Record record) {
        if(record.getValue() == null) {
            return false;
        }
        try {
            new JSONObject(record.getValue());
        } catch (JSONException ex) {
            try {
                new JSONArray(record.getValue());
            } catch (JSONException ex1) {
                return false;
            }
        }
        return true;
    }
}
package org.akhq.utils;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.akhq.models.Record;
@Singleton
@Requires(property = "akhq.security.data-masking.mode", value = "none")
public class NoOpMasker implements Masker {
    @Override
    public Record maskRecord(Record record) {
        return record;
    }
}
package org.akhq.models;
import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryDeserializerDataParser;
import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryKafkaDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema;
import kafka.coordinator.group.GroupMetadataManager;
import kafka.coordinator.transaction.BaseKey;
import kafka.coordinator.transaction.TransactionLog;
import lombok.*;
import org.akhq.configs.SchemaRegistryType;
import org.akhq.utils.AvroToJsonDeserializer;
import org.akhq.utils.AvroToJsonSerializer;
import org.akhq.utils.ContentUtils;
import org.akhq.utils.ProtobufToJsonDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.Deserializer;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public class Record {
    @Setter
    private Topic topic;
    @JsonView(Views.Download.class)
    private int partition;
    @JsonView(Views.Download.class)
    private long offset;
    @JsonView(Views.Download.class)
    private ZonedDateTime timestamp;
    @JsonIgnore
    private TimestampType timestampType;
    @JsonView(Views.Download.class)
    private String keySchemaId;
    private String keySubject;
    @JsonView(Views.Download.class)
    private String valueSchemaId;
    private String valueSubject;
    @JsonView(Views.Download.class)
    private List<KeyValue<String, String>> headers = new ArrayList<>();
    @JsonIgnore
    private Deserializer kafkaAvroDeserializer;
    @JsonIgnore
    private Deserializer kafkaProtoDeserializer;
    @JsonIgnore
    private Deserializer kafkaJsonDeserializer;
    @JsonIgnore
    private AvroToJsonSerializer avroToJsonSerializer;
    @JsonIgnore
    private SchemaRegistryClient client;
    @JsonIgnore
    private ProtobufToJsonDeserializer protobufToJsonDeserializer;
    @JsonIgnore
    private AvroToJsonDeserializer avroToJsonDeserializer;
    @Getter(AccessLevel.NONE)
    private byte[] bytesKey;
    @JsonView(Views.Download.class)
    @Getter(AccessLevel.NONE)
    private String key;
    @Getter(AccessLevel.NONE)
    private byte[] bytesValue;
    @JsonView(Views.Download.class)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private String value;
    @JsonIgnore
    private final List<String> exceptions = new ArrayList<>();
    @Getter(AccessLevel.NONE)
    private byte MAGIC_BYTE;
    @JsonIgnore
    private Boolean truncated;
    @JsonIgnore
    private Deserializer awsGlueKafkaDeserializer;
    public Record(RecordMetadata record, SchemaRegistryType schemaRegistryType, byte[] bytesKey, byte[] bytesValue, List<KeyValue<String, String>> headers, Topic topic, Deserializer awsGlueKafkaDeserializer) {
        this.MAGIC_BYTE = schemaRegistryType.getMagicByte();
        this.topic = topic;
        this.partition = record.partition();
        this.offset = record.offset();
        this.timestamp = ZonedDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp()), ZoneId.systemDefault());
        this.bytesKey = bytesKey;
        this.awsGlueKafkaDeserializer = awsGlueKafkaDeserializer;
        this.keySchemaId = getAvroSchemaId(this.bytesKey);
        this.keySubject = getAvroSchemaSubject(this.keySchemaId, this.bytesKey);
        this.bytesValue = bytesValue;
        this.valueSchemaId = getAvroSchemaId(this.bytesValue);
        this.valueSubject = getAvroSchemaSubject(this.valueSchemaId, this.bytesValue);
        this.headers = headers;
        this.truncated = false;
    }
    public Record(SchemaRegistryClient client, ConsumerRecord<byte[], byte[]> record, SchemaRegistryType schemaRegistryType, Deserializer kafkaAvroDeserializer,
                  Deserializer kafkaJsonDeserializer, Deserializer kafkaProtoDeserializer, AvroToJsonSerializer avroToJsonSerializer,
                  ProtobufToJsonDeserializer protobufToJsonDeserializer, AvroToJsonDeserializer avroToJsonDeserializer, byte[] bytesValue, Topic topic, Deserializer awsGlueKafkaDeserializer) {
        if (schemaRegistryType == SchemaRegistryType.TIBCO) {
            this.MAGIC_BYTE = (byte) 0x80;
        } else {
            this.MAGIC_BYTE = 0x0;
        }
        this.client = client;
        this.topic = topic;
        this.partition = record.partition();
        this.offset = record.offset();
        this.timestamp = ZonedDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp()), ZoneId.systemDefault());
        this.timestampType = record.timestampType();
        this.bytesKey = record.key();
        this.awsGlueKafkaDeserializer = awsGlueKafkaDeserializer;
        this.keySchemaId = getAvroSchemaId(this.bytesKey);
        this.keySubject = getAvroSchemaSubject(this.keySchemaId, this.bytesKey);
        this.bytesValue = bytesValue;
        this.valueSchemaId = getAvroSchemaId(this.bytesValue);
        this.valueSubject = getAvroSchemaSubject(this.valueSchemaId, this.bytesValue);
        for (Header header: record.headers()) {
            String headerValue = String.valueOf(ContentUtils.convertToObject(header.value()));
            this.headers.add(new KeyValue<>(header.key(), headerValue));
        }
        this.kafkaAvroDeserializer = kafkaAvroDeserializer;
        this.protobufToJsonDeserializer = protobufToJsonDeserializer;
        this.avroToJsonDeserializer = avroToJsonDeserializer;
        this.kafkaProtoDeserializer = kafkaProtoDeserializer;
        this.avroToJsonSerializer = avroToJsonSerializer;
        this.kafkaJsonDeserializer = kafkaJsonDeserializer;
        this.truncated = false;
    }
    public String getKey() {
        if (this.key == null) {
            this.key = convertToString(bytesKey, keySchemaId, true);
        }
        return this.key;
    }
    @JsonIgnore
    public String getKeyAsBase64() {
        if (bytesKey == null) {
            return null;
        } else {
            return new String(Base64.getEncoder().encode(bytesKey));
        }
    }
    public String getValue() {
        if (this.value == null) {
            this.value = convertToString(bytesValue, valueSchemaId, false);
        }
        return this.value;
    }
    public void setValue(String value) {
        this.value = value;
    }
    public void setKey(String key) {
        this.key = key;
    }
    public void setTruncated(Boolean truncated) {
        this.truncated = truncated;
    }
    private String convertToString(byte[] payload, String schemaId, boolean isKey) {
        if (payload == null) {
            return null;
        } else if (schemaId != null) {
            try {
                Object toType = null;
                if (this.awsGlueKafkaDeserializer != null) {
                    return this.awsGlueKafkaDeserializer.deserialize(this.topic.getName(), payload).toString();
                }
                if (client != null) {
                    ParsedSchema schema = client.getSchemaById(Integer.parseInt(schemaId));
                    if ( schema.schemaType().equals(ProtobufSchema.TYPE) ) {
                       toType = kafkaProtoDeserializer.deserialize(topic.getName(), payload);
                       if (!(toType instanceof Message)) {
                           return String.valueOf(toType);
                       }
                       Message dynamicMessage = (Message)toType;
                       return avroToJsonSerializer.getMapper().readTree(JsonFormat.printer().print(dynamicMessage)).toString();
                    } else  if ( schema.schemaType().equals(JsonSchema.TYPE) ) {
                      toType = kafkaJsonDeserializer.deserialize(topic.getName(), payload);
                      if ( !(toType instanceof JsonNode) ) {
                          return String.valueOf(toType);
                      }
                      JsonNode node = (JsonNode) toType;
                      return node.toString();
                    }
                }
                toType = kafkaAvroDeserializer.deserialize(topic.getName(), payload);
                //for primitive avro type
                if (!(toType instanceof GenericRecord)) {
                    return String.valueOf(toType);
                }
                GenericRecord record = (GenericRecord) toType;
                return avroToJsonSerializer.toJson(record);
            } catch (Exception exception) {
                this.exceptions.add(exception.getMessage());
                return new String(payload);
            }
        } else if (topic.isInternalTopic() && topic.getName().equals("__consumer_offsets")) {
            try {
                if (isKey) {
                    return GroupMetadataManager.readMessageKey(ByteBuffer.wrap(payload)).key().toString();
                } else {
                    return GroupMetadataManager.readOffsetMessageValue(ByteBuffer.wrap(payload)).toString();
                }
            } catch (Exception exception) {
                this.exceptions.add(Optional.ofNullable(exception.getMessage())
                        .filter(msg -> !msg.isBlank())
                        .orElseGet(() -> exception.getClass().getCanonicalName()));
                return new String(payload);
            }
        } else if (topic.isInternalTopic() && topic.getName().equals("__transaction_state")) {
            try {
                if (isKey) {
                    BaseKey txnKey = TransactionLog.readTxnRecordKey(ByteBuffer.wrap(payload));
                    return avroToJsonSerializer.getMapper().writeValueAsString(
                        Map.of("transactionalId", txnKey.transactionalId(), "version", txnKey.version())
                    );
                } else {
                    BaseKey txnKey = TransactionLog.readTxnRecordKey(ByteBuffer.wrap(this.bytesKey));
                    return avroToJsonSerializer.getMapper().writeValueAsString(TransactionLog.readTxnRecordValue(txnKey.transactionalId(), ByteBuffer.wrap(payload)));
                }
            } catch (Exception exception) {
                this.exceptions.add(Optional.ofNullable(exception.getMessage())
                    .filter(msg -> !msg.isBlank())
                    .orElseGet(() -> exception.getClass().getCanonicalName()));
                return new String(payload);
            }
        } else {
            if (protobufToJsonDeserializer != null) {
                try {
                    String record = protobufToJsonDeserializer.deserialize(topic.getName(), payload, isKey);
                    if (record != null) {
                        return record;
                    }
                } catch (Exception exception) {
                    this.exceptions.add(exception.getMessage());
                    return new String(payload);
                }
            }
            if (avroToJsonDeserializer != null) {
                try {
                    String record = avroToJsonDeserializer.deserialize(topic.getName(), payload, isKey);
                    if (record != null) {
                        return record;
                    }
                } catch (Exception exception) {
                    this.exceptions.add(exception.getMessage());
                    return new String(payload);
                }
            }
            return new String(payload);
        }
    }
    @JsonIgnore
    public Collection<String> getHeadersKeySet() {
        return headers
            .stream()
            .map(KeyValue::getKey)
            .collect(Collectors.toList());
    }
    @JsonIgnore
    public Collection<String> getHeadersValues() {
        return headers
            .stream()
            .map(KeyValue::getValue)
            .collect(Collectors.toList());
    }
    private String getAvroSchemaId(byte[] payload) {
        if (topic.isInternalTopic()) {
            return null;
        }
        try {
            if (awsGlueKafkaDeserializer!= null) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(payload);
                GlueSchemaRegistryDeserializerDataParser dataParser = GlueSchemaRegistryDeserializerDataParser.getInstance();
                UUID schemaVersionId = dataParser.getSchemaVersionId(byteBuffer);
                return schemaVersionId.toString();
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte magicBytes = buffer.get();
            int schemaId = buffer.getInt();
            if (magicBytes == MAGIC_BYTE && schemaId >= 0) {
                return String.valueOf(schemaId);
            }
        } catch (Exception ignore) {
        }
        return null;
    }
    private String getAvroSchemaSubject(String schemaId, byte[] payload) {
        if (schemaId == null || client == null) {
            return null;
        }
        try {
            if(awsGlueKafkaDeserializer!= null) {
                return  ( (GlueSchemaRegistryKafkaDeserializer) awsGlueKafkaDeserializer)
                    .getGlueSchemaRegistryDeserializationFacade().getSchema(payload).getSchemaName();
            }
            ParsedSchema schemaById = client.getSchemaById(Integer.parseInt(schemaId));
            if (schemaById == null) {
                return null;
            }
            return schemaById.name();
        } catch (Exception ignore) {
        }
        return null;
    }
    /**
     * Jackson views declaration
     */
    public static class Views {
        public static class Download {}
    }
}
package org.akhq.repositories;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.sse.Event;
import io.reactivex.Flowable;
import java.util.stream.StreamSupport;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.akhq.configs.SchemaRegistryType;
import org.akhq.controllers.TopicController;
import org.akhq.models.KeyValue;
import org.akhq.models.Partition;
import org.akhq.models.Record;
import org.akhq.models.Topic;
import org.akhq.models.Schema;
import org.akhq.modules.KafkaModule;
import org.akhq.modules.schemaregistry.SchemaSerializer;
import org.akhq.modules.schemaregistry.RecordWithSchemaSerializerFactory;
import org.akhq.utils.AvroToJsonSerializer;
import org.akhq.utils.Debug;
import org.akhq.utils.Masker;
import org.apache.kafka.clients.admin.DeletedRecords;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.codehaus.httpcache4j.uri.URIBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
@Singleton
@Slf4j
public class RecordRepository extends AbstractRepository {
    public static final String SEARCH_SPLIT_REGEX = " (?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)";
    @Inject
    private KafkaModule kafkaModule;
    @Inject
    private ConfigRepository configRepository;
    @Inject
    private AvroToJsonSerializer avroToJsonSerializer;
    @Inject
    private TopicRepository topicRepository;
    @Inject
    private SchemaRegistryRepository schemaRegistryRepository;
    @Inject
    private RecordWithSchemaSerializerFactory serializerFactory;
    @Inject
    private CustomDeserializerRepository customDeserializerRepository;
    @Inject
    private AvroWireFormatConverter avroWireFormatConverter;
    @Inject
    private Masker masker;
    @Value("${akhq.topic-data.poll-timeout:10000}")
    protected int pollTimeout;
    @Value("${akhq.clients-defaults.consumer.properties.max.poll.records:25000}")
    protected int maxPollRecords;
    @Value("${akhq.topic-data.kafka-max-message-length:2147483647}")
    protected int maxKafkaMessageLength;
    public Map<String, Record> getLastRecord(String clusterId, List<String> topicsName) throws ExecutionException, InterruptedException {
        Map<String, Topic> topics = topicRepository.findByName(clusterId, topicsName).stream()
            .collect(Collectors.toMap(Topic::getName, Function.identity()));
        List<TopicPartition> topicPartitions = topics.values()
            .stream()
            .flatMap(topic -> topic.getPartitions().stream())
            .map(partition -> new TopicPartition(partition.getTopic(), partition.getId()))
            .collect(Collectors.toList());
        ConcurrentHashMap<String, Record> records = new ConcurrentHashMap<>();
        try (KafkaConsumer<byte[], byte[]> consumer = kafkaModule.getConsumer(clusterId)) {
            consumer.assign(topicPartitions);
            consumer
                .endOffsets(consumer.assignment())
                .forEach((topicPartition, offset) -> {
                    consumer.seek(topicPartition, Math.max(0, offset - 2));
                });
            this.poll(consumer)
                .forEach(record -> {
                    if (!records.containsKey(record.topic())) {
                        records.put(record.topic(), newRecord(record, clusterId, topics.get(record.topic())));
                    } else {
                        Record current = records.get(record.topic());
                        if (current.getTimestamp().toInstant().toEpochMilli() < record.timestamp()) {
                            records.put(record.topic(), newRecord(record, clusterId, topics.get(record.topic())));
                        }
                    }
                });
        }
        return records;
    }
    public List<Record> consume(String clusterId, Options options) throws ExecutionException, InterruptedException {
        return Debug.call(() -> {
            Topic topicsDetail = topicRepository.findByName(clusterId, options.topic);
            if (options.sort == Options.Sort.OLDEST) {
                return consumeOldest(topicsDetail, options);
            } else {
                return consumeNewest(topicsDetail, options);
            }
        }, "Consume with options {}", Collections.singletonList(options.toString()));
    }
    private List<Record> consumeOldest(Topic topic, Options options) {
        List<Record> list = new ArrayList<>();
        for (Map.Entry<TopicPartition, Long> partition : getTopicPartitionForSortOldest(topic, options).entrySet()) {
            Properties properties = new Properties() {{
                put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, options.size);
            }};
            try (KafkaConsumer<byte[], byte[]> consumer = this.kafkaModule.getConsumer(options.clusterId, properties)) {
                consumer.assign(List.of(partition.getKey()));
                consumer.seek(partition.getKey(), partition.getValue());
                if (log.isTraceEnabled()) {
                    log.trace(
                        "Consume [topic: {}] [partition: {}] [start: {}]",
                        partition.getKey().topic(),
                        partition.getKey().partition(),
                        partition.getValue()
                    );
                }
                ConsumerRecords<byte[], byte[]> records = this.poll(consumer);
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    Record current = newRecord(record, options, topic);
                    if (matchFilters(options, current)) {
                        filterMessageLength(current);
                        list.add(current);
                    }
                }
            }
        }
        return list.stream()
            .sorted(Comparator.comparing(Record::getTimestamp))
            .limit(options.size)
            .toList();
    }
    public List<TimeOffset> getOffsetForTime(String clusterId, List<org.akhq.models.TopicPartition> partitions, Long timestamp) throws ExecutionException, InterruptedException {
        return Debug.call(() -> {
            Map<TopicPartition, Long> map = new HashMap<>();
            try (KafkaConsumer<byte[], byte[]> consumer = this.kafkaModule.getConsumer(clusterId)) {
                partitions
                    .forEach(partition -> map.put(
                        new TopicPartition(partition.getTopic(), partition.getPartition()),
                        timestamp
                    ));
                List<TimeOffset> collect = consumer.offsetsForTimes(map)
                    .entrySet()
                    .stream()
                    .map(r -> r.getValue() != null ? new TimeOffset(
                        r.getKey().topic(),
                        r.getKey().partition(),
                        r.getValue().offset()
                    ) : null)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                return collect;
            }
        }, "Offsets for " + partitions + " Timestamp " + timestamp, null);
    }
    public Optional<Record> consumeSingleRecord(String clusterId, Topic topic, Options options) throws ExecutionException, InterruptedException {
        return Debug.call(() -> {
            Optional<Record> singleRecord = Optional.empty();
            Map<TopicPartition, Long> partitions = getTopicPartitionForSortOldest(topic, options);
            Properties properties = new Properties() {{
                put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
            }};
            try (KafkaConsumer<byte[], byte[]> consumer = kafkaModule.getConsumer(clusterId, properties)) {
                consumer.assign(partitions.keySet());
                partitions.forEach(consumer::seek);
                ConsumerRecords<byte[], byte[]> records = this.poll(consumer);
                if (!records.isEmpty()) {
                    singleRecord = Optional.of(newRecord(records.iterator().next(), options, topic));
                }
            }
            return singleRecord;
        }, "Consume with options {}", Collections.singletonList(options.toString()));
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    @AllArgsConstructor
    public static class TimeOffset {
        private final String topic;
        private final int partition;
        private final long offset;
    }
    private Map<TopicPartition, Long> getTopicPartitionForSortOldest(Topic topic, Options options) {
        Properties properties = new Properties() {{
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        }};
        try (KafkaConsumer<byte[], byte[]> consumer = kafkaModule.getConsumer(options.clusterId, properties)) {
            return topic
                .getPartitions()
                .stream()
                .map(partition -> getFirstOffsetForSortOldest(consumer, partition, options)
                    .map(offsetBound -> offsetBound.withTopicPartition(
                        new TopicPartition(
                            partition.getTopic(),
                            partition.getId()
                        )
                    ))
                )
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toMap(OffsetBound::getTopicPartition, OffsetBound::getBegin));
        }
    }
    private List<Record> consumeNewest(Topic topic, Options options) {
        return topic
            .getPartitions()
            .parallelStream()
            .map(partition -> {
                KafkaConsumer<byte[], byte[]> consumer =
                    this.kafkaModule.getConsumer(options.clusterId, new Properties() {{
                        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, options.size);
                    }});
                return getOffsetForSortNewest(consumer, partition, options)
                        .map(offset -> offset.withTopicPartition(
                            new TopicPartition(
                                partition.getTopic(),
                                partition.getId()
                            )
                        ));
                }
            )
            .filter(Optional::isPresent)
            .map(Optional::get)
            .flatMap(topicPartitionOffset -> {
                topicPartitionOffset.getConsumer().assign(Collections.singleton(topicPartitionOffset.getTopicPartition()));
                topicPartitionOffset.getConsumer().seek(topicPartitionOffset.getTopicPartition(), topicPartitionOffset.getBegin());
                if (log.isTraceEnabled()) {
                    log.trace(
                        "Consume Newest [topic: {}] [partition: {}] [start: {}]",
                        topicPartitionOffset.getTopicPartition().topic(),
                        topicPartitionOffset.getTopicPartition().partition(),
                        topicPartitionOffset.getBegin()
                    );
                }
                List<Record> list = new ArrayList<>();
                int emptyPoll = 0;
                do {
                    ConsumerRecords<byte[], byte[]> records;
                    records = this.poll(topicPartitionOffset.getConsumer());
                    if (records.isEmpty()) {
                        emptyPoll++;
                    } else {
                        if (log.isTraceEnabled()) {
                            log.trace(
                                "Empty pool [topic: {}] [partition: {}]",
                                topicPartitionOffset.getTopicPartition().topic(),
                                topicPartitionOffset.getTopicPartition().partition()
                            );
                        }
                        emptyPoll = 0;
                    }
                    for (ConsumerRecord<byte[], byte[]> record : records) {
                        if (record.offset() > topicPartitionOffset.getEnd()) {
                            emptyPoll = 2;
                            break;
                        }
                        Record current = newRecord(record, options, topic);
                        if (matchFilters(options, current)) {
                            filterMessageLength(current);
                            list.add(current);
                        }
                        // End of the partition, we can stop here
                        if (record.offset() == topicPartitionOffset.getEnd()) {
                            emptyPoll = 1;
                            break;
                        }
                    }
                }
                while (emptyPoll < 1);
                Collections.reverse(list);
                topicPartitionOffset.getConsumer().close();
                return Stream.of(list);
            })
            .flatMap(List::stream)
            .sorted(Comparator.comparing(Record::getTimestamp).reversed())
            .limit(options.size)
            .collect(Collectors.toList());
    }
    private Optional<Long> getFirstOffset(KafkaConsumer<byte[], byte[]> consumer, Partition partition, Options options) {
        if (options.partition != null && partition.getId() != options.partition) {
            return Optional.empty();
        }
        long first = partition.getFirstOffset();
        if (options.timestamp != null) {
            Map<TopicPartition, OffsetAndTimestamp> timestampOffset = consumer.offsetsForTimes(
                ImmutableMap.of(
                    new TopicPartition(partition.getTopic(), partition.getId()),
                    options.timestamp
                )
            );
            for (Map.Entry<TopicPartition, OffsetAndTimestamp> entry : timestampOffset.entrySet()) {
                if (entry.getValue() == null) {
                    return Optional.empty();
                }
                first = entry.getValue().offset();
            }
        }
        return Optional.of(first);
    }
    private Optional<OffsetBound> getFirstOffsetForSortOldest(KafkaConsumer<byte[], byte[]> consumer, Partition partition, Options options) {
        return getFirstOffset(consumer, partition, options)
            .map(first -> {
                if (options.after.size() > 0 && options.after.containsKey(partition.getId())) {
                    first = options.after.get(partition.getId()) + 1;
                }
                if (first > partition.getLastOffset()) {
                    return null;
                }
                return OffsetBound.builder()
                    .begin(first)
                    .build();
            });
    }
    private Optional<EndOffsetBound> getOffsetForSortNewest(KafkaConsumer<byte[], byte[]> consumer, Partition partition, Options options) {
        return getFirstOffset(consumer, partition, options)
            .map(first -> {
                // Take end offset - 1 to get the last record offset
                long last = partition.getLastOffset() - 1;
                // If there is an after parameter in the request use this one
                if (options.after.containsKey(partition.getId())) {
                    last = options.after.get(partition.getId()) - 1;
                }
                if (last < 0) {
                    consumer.close();
                    return null;
                } else if (!(last - options.getSize() < first)) {
                    first = last - options.getSize() + 1;
                }
                return EndOffsetBound.builder()
                    .consumer(consumer)
                    .begin(first)
                    .end(last)
                    .build();
            });
    }
    @SuppressWarnings("deprecation")
    private ConsumerRecords<byte[], byte[]> poll(KafkaConsumer<byte[], byte[]> consumer) {
        /*
        // poll with long call poll(final long timeoutMs, boolean includeMetadataInTimeout = true)
        // poll with Duration call poll(final long timeoutMs, boolean includeMetadataInTimeout = false)
        // So second one don't wait for metadata and return empty records
        // First one wait for metadata and send records
        // Hack bellow can be used to wait for metadata
        */
        return consumer.poll(this.pollTimeout);
        /*
        if (!records.isEmpty()) {
            return records;
        }
        Field field = consumer.getClass().getDeclaredField("client");
        field.setAccessible(true);
        ConsumerNetworkClient client = (ConsumerNetworkClient) field.get(consumer);
        while(!client.hasReadyNodes(System.currentTimeMillis())) {
            Thread.sleep(100);
        }
        return consumer.poll(Duration.ofMillis(2000));
        */
    }
    private Record newRecord(ConsumerRecord<byte[], byte[]> record, String clusterId, Topic topic) {
        SchemaRegistryType schemaRegistryType = this.schemaRegistryRepository.getSchemaRegistryType(clusterId);
        SchemaRegistryClient client = this.kafkaModule.getRegistryClient(clusterId);
        return masker.maskRecord(new Record(
            client,
            record,
            this.schemaRegistryRepository.getSchemaRegistryType(clusterId),
            this.schemaRegistryRepository.getKafkaAvroDeserializer(clusterId),
            schemaRegistryType == SchemaRegistryType.CONFLUENT? this.schemaRegistryRepository.getKafkaJsonDeserializer(clusterId):null,
            schemaRegistryType == SchemaRegistryType.CONFLUENT? this.schemaRegistryRepository.getKafkaProtoDeserializer(clusterId):null,
            this.avroToJsonSerializer,
            this.customDeserializerRepository.getProtobufToJsonDeserializer(clusterId),
            this.customDeserializerRepository.getAvroToJsonDeserializer(clusterId),
            avroWireFormatConverter.convertValueToWireFormat(record, client,
                    this.schemaRegistryRepository.getSchemaRegistryType(clusterId)),
            topic,
            schemaRegistryType == SchemaRegistryType.GLUE ? schemaRegistryRepository.getAwsGlueKafkaDeserializer(clusterId): null
        ));
    }
    private Record newRecord(ConsumerRecord<byte[], byte[]> record, BaseOptions options, Topic topic) {
        SchemaRegistryType schemaRegistryType = this.schemaRegistryRepository.getSchemaRegistryType(options.clusterId);
        SchemaRegistryClient client = this.kafkaModule.getRegistryClient(options.clusterId);
        return masker.maskRecord(new Record(
            client,
            record,
            schemaRegistryType,
            this.schemaRegistryRepository.getKafkaAvroDeserializer(options.clusterId),
            schemaRegistryType == SchemaRegistryType.CONFLUENT? this.schemaRegistryRepository.getKafkaJsonDeserializer(options.clusterId):null,
            schemaRegistryType == SchemaRegistryType.CONFLUENT? this.schemaRegistryRepository.getKafkaProtoDeserializer(options.clusterId):null,
            this.avroToJsonSerializer,
            this.customDeserializerRepository.getProtobufToJsonDeserializer(options.clusterId),
            this.customDeserializerRepository.getAvroToJsonDeserializer(options.clusterId),
            avroWireFormatConverter.convertValueToWireFormat(record, client,
                    this.schemaRegistryRepository.getSchemaRegistryType(options.clusterId)),
            topic,
            schemaRegistryType == SchemaRegistryType.GLUE ? schemaRegistryRepository.getAwsGlueKafkaDeserializer(options.getClusterId()): null
        ));
    }
    public List<RecordMetadata> produce(
        String clusterId,
        String topic,
        Optional<String> value,
        List<KeyValue<String, String>> headers,
        Optional<String> key,
        Optional<Integer> partition,
        Optional<Long> timestamp,
        Optional<String> keySchema,
        Optional<String> valueSchema,
        Boolean multiMessage,
        Optional<String> keyValueSeparator) throws ExecutionException, InterruptedException, RestClientException, IOException {
        List<RecordMetadata> produceResults = new ArrayList<>();
        // Distinguish between single record produce, and multiple messages
        if (Boolean.TRUE.equals(multiMessage) && value.isPresent()) {
            // Split key-value pairs and produce them
            for (KeyValue<String, String> kvPair : splitMultiMessage(value.get(), keyValueSeparator.orElseThrow())) {
                produceResults.add(produce(clusterId, topic, Optional.of(kvPair.getValue()), headers, Optional.of(kvPair.getKey()),
                    partition, timestamp, keySchema, valueSchema));
            }
        } else {
            produceResults.add(
                produce(clusterId, topic, value, headers, key, partition, timestamp, keySchema, valueSchema));
        }
        return produceResults;
    }
    private RecordMetadata produce(
        String clusterId,
        String topic, byte[] value,
        List<KeyValue<String, String>> headers,
        byte[] key,
        Optional<Integer> partition,
        Optional<Long> timestamp
    ) throws ExecutionException, InterruptedException {
        return kafkaModule
            .getProducer(clusterId)
            .send(new ProducerRecord<>(
                topic,
                partition.orElse(null),
                timestamp.orElse(null),
                key,
                value,
                headers == null ? Collections.emptyList() : headers
                    .stream()
                    .filter(entry -> StringUtils.isNotEmpty(entry.getKey()))
                    .map(entry -> new RecordHeader(
                        entry.getKey(),
                        entry.getValue() == null ? null : entry.getValue().getBytes()
                    ))
                    .collect(Collectors.toList())
            ))
            .get();
    }
    /**
     * Splits a multi-message into a list of key-value pairs.
     * @param value The multi-message string submitted by the {@link TopicController}
     * @param keyValueSeparator The character(s) separating each key from their corresponding value
     * @return A list of {@link KeyValue}, holding the split pairs
     */
    private List<KeyValue<String, String>> splitMultiMessage(String value, String keyValueSeparator) {
        return List.of(value.split("\r\n|\r|\n")).stream().map(v -> splitKeyValue(v, keyValueSeparator))
                .collect(Collectors.toList());
    }
    private KeyValue<String, String> splitKeyValue(String keyValueStr, String keyValueSeparator) {
        String[] keyValue = null;
        keyValue = keyValueStr.split(keyValueSeparator, 2);
        return new KeyValue<>(keyValue[0].trim(),keyValue[1]);
    }
    public void emptyTopic(String clusterId, String topicName) throws ExecutionException, InterruptedException {
        Map<TopicPartition, RecordsToDelete> recordsToDelete = new HashMap<>();
        var topic = topicRepository.findByName(clusterId, topicName);
        topic.getPartitions().forEach(partition -> {
            recordsToDelete.put(new TopicPartition(partition.getTopic(), partition.getId()),
                    RecordsToDelete.beforeOffset(partition.getLastOffset()));
        });
        deleteRecords(clusterId, recordsToDelete);
    }
    public void emptyTopicByTimestamp(String clusterId,
                                      String topicName,
                                      Long timestamp) throws ExecutionException, InterruptedException {
        Map<TopicPartition, Long> timestamps = new HashMap<>();
        Map<TopicPartition, RecordsToDelete> recordsToDelete = new HashMap<>();
        var topic = topicRepository.findByName(clusterId, topicName);
        topic.getPartitions().forEach(partition -> {
            timestamps.put(new TopicPartition(partition.getTopic(), partition.getId()),
                            timestamp);
        });
        Map<TopicPartition, OffsetAndTimestamp> offsets = kafkaModule.getConsumer(clusterId).offsetsForTimes(timestamps);
        offsets.forEach((topicPartition, offsetAndTimestamp) -> {
            recordsToDelete.put(topicPartition, RecordsToDelete.beforeOffset(offsetAndTimestamp.offset()));
        });
        deleteRecords(clusterId, recordsToDelete);
    }
    private void deleteRecords(String clusterId, Map<TopicPartition, RecordsToDelete> recordsToDelete) throws InterruptedException, ExecutionException {
        var deleted = kafkaModule.getAdminClient(clusterId).deleteRecords(recordsToDelete).lowWatermarks();
        for (Map.Entry<TopicPartition, KafkaFuture<DeletedRecords>> entry : deleted.entrySet()){
            log.debug(entry.getKey().topic() + " " + entry.getKey().partition() + " " + entry.getValue().get().lowWatermark());
        }
    }
    public RecordMetadata produce(
        String clusterId,
        String topic,
        Optional<String> value,
        List<KeyValue<String, String>> headers,
        Optional<String> key,
        Optional<Integer> partition,
        Optional<Long> timestamp,
        Optional<String> keySchema,
        Optional<String> valueSchema
    ) throws ExecutionException, InterruptedException, RestClientException, IOException {
        byte[] keyAsBytes = null;
        byte[] valueAsBytes;
        if (key.isPresent()) {
            if (keySchema.isPresent() && StringUtils.isNotEmpty(keySchema.get())) {
                Schema schema = schemaRegistryRepository.getLatestVersion(clusterId, keySchema.get());
                SchemaSerializer keySerializer = serializerFactory.createSerializer(clusterId, schema.getId());
                keyAsBytes = keySerializer.serialize(key.get());
            } else {
                keyAsBytes = key.filter(Predicate.not(String::isEmpty)).map(String::getBytes).orElse(null);
            }
        } else {
            try {
                if (Topic.isCompacted(configRepository.findByTopic(clusterId, value.isEmpty() ? null : value.get()))) {
                    throw new IllegalArgumentException("Key missing for produce onto compacted topic");
                }
            } catch (ExecutionException ex) {
                log.debug("Failed to determine if {} topic {} is compacted", clusterId, topic, ex);
            }
        }
        if (value.isPresent() && valueSchema.isPresent() && StringUtils.isNotEmpty(valueSchema.get())) {
            Schema schema = schemaRegistryRepository.getLatestVersion(clusterId, valueSchema.get());
            SchemaSerializer valueSerializer = serializerFactory.createSerializer(clusterId, schema.getId());
            valueAsBytes = valueSerializer.serialize(value.get());
        } else {
            valueAsBytes = value.filter(Predicate.not(String::isEmpty)).map(String::getBytes).orElse(null);
        }
        return produce(clusterId, topic, valueAsBytes, headers, keyAsBytes, partition, timestamp);
    }
    public RecordMetadata delete(String clusterId, String topic, Integer partition, byte[] key) throws ExecutionException, InterruptedException {
        return kafkaModule.getProducer(clusterId).send(new ProducerRecord<>(
            topic,
            partition,
            key,
            null
        )).get();
    }
    public Flowable<Event<SearchEvent>> search(Topic topic, Options options) throws ExecutionException, InterruptedException {
        AtomicInteger matchesCount = new AtomicInteger();
        return Flowable.generate(() -> {
            Map<TopicPartition, Long> partitions = getTopicPartitionForSortOldest(topic, options);
            KafkaConsumer<byte[], byte[]> consumer = this.kafkaModule.getConsumer(options.clusterId);
            if (partitions.size() == 0) {
                return new SearchState(consumer, null);
            }
            consumer.assign(partitions.keySet());
            partitions.forEach(consumer::seek);
            partitions.forEach((topicPartition, first) ->
                log.trace(
                    "Search [topic: {}] [partition: {}] [start: {}]",
                    topicPartition.topic(),
                    topicPartition.partition(),
                    first
                )
            );
            return new SearchState(consumer, new SearchEvent(topic));
        }, (searchState, emitter) -> {
            SearchEvent searchEvent = searchState.getSearchEvent();
            KafkaConsumer<byte[], byte[]> consumer = searchState.getConsumer();
            // end
            if (searchEvent == null || searchEvent.emptyPoll >= 1) {
                emitter.onNext(new SearchEvent(topic).end(searchEvent != null ? searchEvent.after: null));
                emitter.onComplete();
                consumer.close();
                return new SearchState(consumer, searchEvent);
            }
            SearchEvent currentEvent = new SearchEvent(searchEvent);
            ConsumerRecords<byte[], byte[]> records = this.poll(consumer);
            if (records.isEmpty()) {
                currentEvent.emptyPoll = 1;
            } else {
                currentEvent.emptyPoll = 0;
            }
            Comparator<Record> comparator = Comparator.comparing(Record::getTimestamp);
            List<Record> sortedRecords = StreamSupport.stream(records.spliterator(), false)
                .map(record -> newRecord(record, options, topic))
                .sorted(Options.Sort.NEWEST.equals(options.sort) ? comparator.reversed() : comparator)
                .toList();
            List<Record> list = new ArrayList<>();
            for (Record record : sortedRecords) {
                if (matchesCount.get() >= options.size) {
                    break;
                }
                currentEvent.updateProgress(record);
                if (matchFilters(options, record)) {
                    list.add(record);
                    matchesCount.getAndIncrement();
                    log.trace(
                        "Record [topic: {}] [partition: {}] [offset: {}] [key: {}]",
                        record.getTopic(),
                        record.getPartition(),
                        record.getOffset(),
                        record.getKey()
                    );
                }
            }
            currentEvent.records = list;
            // No more records, poll was empty: stop here
            if (currentEvent.emptyPoll == 1) {
                emitter.onNext(currentEvent.end(searchEvent.getAfter()));
            }
            // More records than expected, send the records and then stop
            else if (matchesCount.get() >= options.getSize()) {
                currentEvent.emptyPoll = 666;
                emitter.onNext(currentEvent.progress(options));
            }
            // Continue to search
            else {
                emitter.onNext(currentEvent.progress(options));
            }
            return new SearchState(consumer, currentEvent);
        });
    }
    private boolean matchFilters(BaseOptions options, Record record) {
        if (options.getSearch() != null) {
            return matchFilter(options.getSearch(), Arrays.asList(record.getKey(), record.getValue()));
        } else {
            if (options.getSearchByKey() != null) {
                if (!matchFilter(options.getSearchByKey(), Collections.singletonList(record.getKey()))) {
                    return false;
                }
            }
            if (options.getSearchByValue() != null) {
                if (!matchFilter(options.getSearchByValue(), Collections.singletonList(record.getValue()))) {
                    return false;
                }
            }
            if (options.getSearchByHeaderKey() != null) {
                if (!matchFilter(options.getSearchByHeaderKey(), record.getHeadersKeySet())) {
                    return false;
                }
            }
            if (options.getSearchByHeaderValue() != null) {
                if (!matchFilter(options.getSearchByHeaderValue(), record.getHeadersValues())) {
                    return false;
                }
            }
            if (options.getSearchByKeySubject() != null) {
                if (!matchFilter(options.getSearchByKeySubject(), Collections.singletonList(record.getKeySubject()))) {
                    return false;
                }
            }
            if (options.getSearchByValueSubject() != null) {
                return matchFilter(options.getSearchByValueSubject(), Collections.singletonList(record.getValueSubject()));
            }
        }
        return true;
    }
    private boolean matchFilters(Options options, Record record) {
        if (!matchFilters((BaseOptions) options, record)) {
            return false;
        }
        if (options.getEndTimestamp() != null) {
            return record.getTimestamp().toInstant().toEpochMilli() <= options.getEndTimestamp();
        }
        return true;
    }
    private boolean matchFilter(Search searchFilter, Collection<String> stringsToSearch) {
        switch (searchFilter.searchMatchType) {
            case EQUALS:
                return equalsAll(searchFilter.getText(), stringsToSearch);
            case NOT_CONTAINS:
                return notContainsAll(searchFilter.getText(), stringsToSearch);
            default:
                return containsAll(searchFilter.getText(), stringsToSearch);
        }
    }
    /**
     * Check that one of the input strings contains at least one time the patterns in the search string
     * Patterns are extracted from the search string based on whitespace unless enclosed with double quotes
     *
     * @param search - the search string
     * @param in - all the input string to check
     * @return true if input matches at least one time the patterns
     */
    private boolean containsAll(String search, Collection<String> in) {
        if (search.equals("null")) {
            return in
                .stream()
                .allMatch(Objects::isNull);
        }
        return in.parallelStream()
    		.filter(Objects::nonNull)
            .anyMatch(s -> extractSearchPatterns(search)
                .stream()
                .anyMatch(s.toLowerCase()::contains));
    }
    /**
     * Check that one of the input strings matches exactly at least one time the patterns in the search string
     * Patterns are extracted from the search string based on whitespace unless enclosed with double quotes
     *
     * @param search - the search string
     * @param in - all the input string to check
     * @return true if one of the input matches exactly at least one time the patterns
     */
    private boolean equalsAll(String search, Collection<String> in) {
        if (search.equals("null")) {
            return in
                .stream()
                .allMatch(Objects::isNull);
        }
        return in.parallelStream().filter(Objects::nonNull)
            .anyMatch(s -> extractSearchPatterns(search).contains(s.toLowerCase()));
    }
    /**
     * Check that one of the input strings does not contain at least one time the patterns in the search string
     * Patterns are extracted from the search string based on whitespace unless enclosed with double quotes
     *
     * @param search - the search string
     * @param in - all the input string to check
     * @return true if input does not contain at least one time the patterns
     */
    private boolean notContainsAll(String search, Collection<String> in) {
        if (search.equals("null")) {
            return in
                .stream()
                .noneMatch(Objects::isNull);
        }
        return in.parallelStream()
            .filter(Objects::nonNull)
            .anyMatch(s -> extractSearchPatterns(search)
                .stream()
                .noneMatch(s.toLowerCase()::contains));
    }
    /**
     * Extract search patterns from the search string by splitting on whitespace
     * If a pattern is enclosed with double quotes, the white space will be ignored during splitting
     *
     * @param searchString the search string to split into patterns
     * @return search patterns
     */
    private List<String> extractSearchPatterns(String searchString) {
        return Arrays.stream(searchString.toLowerCase().split(SEARCH_SPLIT_REGEX, -1))
            .map(s -> {
                // Update pattern enclosed with double quotes by removing backslashes and start/end double quotes
                s = s.replaceAll("\\\\", "");
                return s.startsWith("\"") ? s.substring(1, s.length() - 1) : s;
            }).collect(Collectors.toList());
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    public static class SearchEvent {
        private Map<Integer, Offset> offsets = new HashMap<>();
        private List<Record> records = new ArrayList<>();
        private String after;
        private double percent;
        private int emptyPoll = 0;
        private SearchEvent(SearchEvent event) {
            this.offsets = event.offsets;
        }
        private SearchEvent(Topic topic) {
            topic.getPartitions()
                .forEach(partition -> {
                    offsets.put(partition.getId(), new Offset(partition.getFirstOffset(), partition.getFirstOffset(), partition.getLastOffset()));
                });
        }
        public Event<SearchEvent> end(String after) {
            this.percent = 100;
            this.after = after;
            return Event.of(this).name("searchEnd");
        }
        public Event<SearchEvent> progress(Options options) {
            long total = 0;
            long current = 0;
            for (Map.Entry<Integer, Offset> item : this.offsets.entrySet()) {
                total += item.getValue().end - item.getValue().begin;
                current += item.getValue().current - item.getValue().begin;
            }
            this.percent = (double) (current * 100) / total;
            this.after = options.pagination(offsets);
            return Event.of(this).name("searchBody");
        }
        private void updateProgress(Record record) {
            Offset offset = this.offsets.get(record.getPartition());
            offset.current = record.getOffset();
        }
        @AllArgsConstructor
        @Setter
        public static class Offset {
            @JsonProperty("begin")
            private final long begin;
            @JsonProperty("current")
            private long current;
            @JsonProperty("end")
            private final long end;
        }
    }
    public Flowable<Event<TailEvent>> tail(String clusterId, TailOptions options) {
        return Flowable.generate(() -> {
            KafkaConsumer<byte[], byte[]> consumer = this.kafkaModule.getConsumer(options.clusterId);
            Map<String, Topic> topics = topicRepository.findByName(clusterId, options.topics).stream()
                    .collect(Collectors.toMap(Topic::getName, Function.identity()));
            consumer
                .assign(topics.values()
                    .stream()
                    .flatMap(topic -> topic.getPartitions()
                        .stream()
                        .map(partition -> new TopicPartition(topic.getName(), partition.getId()))
                    )
                    .collect(Collectors.toList())
                );
            if (options.getAfter() != null) {
                options
                    .getAfter()
                    .forEach(s -> {
                        String[] split = s.split(",");
                        consumer.seek(
                            new TopicPartition(split[0], Integer.parseInt(split[1])),
                            Long.parseLong(split[2])
                        );
                    });
            }
            return new TailState(consumer, new TailEvent(), topics);
        }, (state, subscriber) -> {
            ConsumerRecords<byte[], byte[]> records = this.poll(state.getConsumer());
            TailEvent tailEvent = state.getTailEvent();
            List<Record> list = new ArrayList<>();
            for (ConsumerRecord<byte[], byte[]> record : records) {
                tailEvent.offsets.put(
                    ImmutableMap.of(
                        record.topic(),
                        record.partition()
                    ),
                    record.offset()
                );
                Record current = newRecord(record, options, state.getTopics().get(record.topic()));
                if (matchFilters(options, current)) {
                    list.add(current);
                    log.trace(
                        "Record [topic: {}] [partition: {}] [offset: {}] [key: {}]",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        record.key()
                    );
                }
            }
            tailEvent.records = list;
            subscriber.onNext(Event.of(tailEvent).name("tailBody"));
            state.tailEvent = tailEvent;
            return state;
        });
    }
    public CopyResult copy(Topic fromTopic, String toClusterId, Topic toTopic, List<TopicController.OffsetCopy> offsets, RecordRepository.Options options) {
        Map<TopicPartition, Long> partitions = getTopicPartitionForSortOldest(fromTopic, options);
        Map<TopicPartition, Long> filteredPartitions = partitions.entrySet().stream()
            .filter(topicPartitionLongEntry -> offsets.stream()
                .anyMatch(offsetCopy -> offsetCopy.getPartition() == topicPartitionLongEntry.getKey().partition()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Properties properties = new Properties() {{
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        }};
        try (KafkaConsumer<byte[], byte[]> consumer = this.kafkaModule.getConsumer(options.clusterId, properties)) {
            int counter = 0;
            if (filteredPartitions.size() > 0) {
                consumer.assign(filteredPartitions.keySet());
                filteredPartitions.forEach(consumer::seek);
                if (log.isTraceEnabled()) {
                    filteredPartitions.forEach((topicPartition, first) ->
                        log.trace(
                            "Consume [topic: {}] [partition: {}] [start: {}]",
                            topicPartition.topic(),
                            topicPartition.partition(),
                            first
                        )
                    );
                }
                Map<Partition, Long> partitionsLastOffsetMap = fromTopic.getPartitions()
                    .stream()
                    .collect(Collectors.toMap(Function.identity(), Partition::getLastOffset));
                boolean samePartition = toTopic.getPartitions().size() == fromTopic.getPartitions().size();
                KafkaProducer<byte[], byte[]> producer = kafkaModule.getProducer(toClusterId);
                ConsumerRecords<byte[], byte[]> records;
                do {
                    records = this.pollAndFilter(consumer, partitionsLastOffsetMap);
                    for (ConsumerRecord<byte[], byte[]> record : records) {
                        System.out.println(record.offset() + "-" + record.partition());
                        counter++;
                        producer.send(new ProducerRecord<>(
                            toTopic.getName(),
                            samePartition ? record.partition() : null,
                            record.timestamp(),
                            record.key(),
                            record.value(),
                            record.headers()
                        ));
                    }
                } while (!records.isEmpty());
                producer.flush();
            }
            return new CopyResult(counter);
        }
    }
    /**
     * Polls the records and filters them with a maximum offset
     *
     * @param consumer
     * @param partitionsLastOffsetMap key : partition, value : the maximum offset we want to reach
     * @return filtered records after polled. And an empty one if there are no records polled
     * or if every record has been filtered
     */
    private ConsumerRecords<byte[], byte[]> pollAndFilter(KafkaConsumer<byte[], byte[]> consumer, Map<Partition, Long> partitionsLastOffsetMap) {
        ConsumerRecords<byte[], byte[]> records = this.poll(consumer);
        return new ConsumerRecords<>(partitionsLastOffsetMap.entrySet()
            .stream()
            .map(entry ->
                {
                    // We filter records by partition
                    TopicPartition topicPartition = new TopicPartition(entry.getKey().getTopic(), entry.getKey().getId());
                    return Map.entry(topicPartition, records.records(topicPartition)
                        .stream()
                        .filter(consumerRecord -> consumerRecord.offset() < entry.getValue())
                        .collect(Collectors.toList()));
                }
            ).filter(entry -> !entry.getValue().isEmpty())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }
    @ToString
    @EqualsAndHashCode
    @AllArgsConstructor
    @Getter
    public static class CopyResult {
        int records;
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    @AllArgsConstructor
    public static class TailState {
        private final KafkaConsumer<byte[], byte[]> consumer;
        private TailEvent tailEvent;
        private Map<String, Topic> topics;
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    @AllArgsConstructor
    public static class SearchState {
        private final KafkaConsumer<byte[], byte[]> consumer;
        private final SearchEvent searchEvent;
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    public static class TailEvent {
        private List<Record> records = new ArrayList<>();
        private final Map<Map<String, Integer>, Long> offsets = new HashMap<>();
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    public static class Search {
        public enum SearchMatchType {
            EQUALS("E"),
            CONTAINS("C"),
            NOT_CONTAINS("N");
            private final String code;
            SearchMatchType(String code) {
                this.code = code;
            }
            public static SearchMatchType valueOfCode(String code) {
                for (SearchMatchType e : values()) {
                    if (e.code.equals(code)) {
                        return e;
                    }
                }
                return null;
            }
        }
        protected String text;
        protected SearchMatchType searchMatchType;
        public Search(String text) {
            this.setText(text);
            this.searchMatchType = SearchMatchType.CONTAINS;
        }
        public Search(String text, String searchMatchType) {
            this.setText(text);
            this.setSearchMatchType(searchMatchType);
        }
        public void setText(String text) {
            this.text = text;
        }
        public void setSearchMatchType(String type) {
            this.searchMatchType = SearchMatchType.valueOfCode(type);
        }
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    @Setter
    abstract public static class BaseOptions {
        protected String clusterId;
        protected Search search;
        protected Search searchByKey;
        protected Search searchByValue;
        protected Search searchByHeaderKey;
        protected Search searchByHeaderValue;
        protected Search searchByKeySubject;
        protected Search searchByValueSubject;
        public BaseOptions() {
        }
        public void setSearchByKey(String searchByKey) {
           this.searchByKey = this.buildSearch(searchByKey);
        }
        public void setSearchByValue(String searchByValue) {
            this.searchByValue = this.buildSearch(searchByValue);
        }
        public void setSearchByHeaderKey(String searchByHeaderKey) {
            this.searchByHeaderKey = this.buildSearch(searchByHeaderKey);
        }
        public void setSearchByHeaderValue(String searchByHeaderValue) {
            this.searchByHeaderValue = this.buildSearch(searchByHeaderValue);
        }
        public void setSearchByKeySubject(String searchByKeySubject) {
            this.searchByKeySubject = this.buildSearch(searchByKeySubject);
        }
        public void setSearchByValueSubject(String searchByValueSchemaName) {
            this.searchByValueSubject = this.buildSearch(searchByValueSchemaName);
        }
        public void setSearch(String search) {
            this.search = new Search(search);
        }
        private Search buildSearch(String search) {
            int sepPos = search.lastIndexOf('_');
            if(sepPos > 0) {
                return new Search(search.substring(0, sepPos), search.substring(sepPos + 1));
            } else {
                return new Search(search);
            }
        }
    }
    @ToString
    @EqualsAndHashCode(callSuper = true)
    @Getter
    @Setter
    public static class Options extends BaseOptions {
        public enum Sort {
            OLDEST,
            NEWEST,
        }
        private String topic;
        private int size;
        private Map<Integer, Long> after = new HashMap<>();
        private Sort sort;
        private Integer partition;
        private Long timestamp;
        private Long endTimestamp;
        public Options(Environment environment, String clusterId, String topic) {
            this.sort = Sort.OLDEST;
            //noinspection ConstantConditions
            this.size = environment.getProperty("akhq.topic-data.size", Integer.class, 50);
            this.clusterId = clusterId;
            this.topic = topic;
        }
        public void setAfter(String after) {
            this.after.clear();
            //noinspection UnstableApiUsage
            Splitter.on('_')
                .withKeyValueSeparator('-')
                .split(after)
                .forEach((key, value) -> this.after.put(Integer.valueOf(key), Long.valueOf(value)));
        }
        public String pagination(Map<Integer, SearchEvent.Offset> offsets) {
            Map<Integer, Long> next = new HashMap<>(this.after);
            for (Map.Entry<Integer, SearchEvent.Offset> offset : offsets.entrySet()) {
                if (this.sort == Sort.OLDEST && (!next.containsKey(offset.getKey()) || next.get(offset.getKey()) < offset.getValue().current)) {
                    next.put(offset.getKey(), offset.getValue().current);
                } else if (this.sort == Sort.NEWEST && (!next.containsKey(offset.getKey()) || next.get(offset.getKey()) > offset.getValue().current)) {
                    next.put(offset.getKey(), offset.getValue().current);
                }
            }
            return paginationLink(next);
        }
        public String pagination(List<Record> records) {
            Map<Integer, Long> next = new HashMap<>(this.after);
            for (Record record : records) {
                if (this.sort == Sort.OLDEST && (!next.containsKey(record.getPartition()) || next.get(record.getPartition()) < record.getOffset())) {
                    next.put(record.getPartition(), record.getOffset());
                } else if (this.sort == Sort.NEWEST && (!next.containsKey(record.getPartition()) || next.get(record.getPartition()) > record.getOffset())) {
                    next.put(record.getPartition(), record.getOffset());
                }
            }
            return paginationLink(next);
        }
        private String paginationLink(Map<Integer, Long> next) {
            ArrayList<String> segment = new ArrayList<>();
            for (Map.Entry<Integer, Long> offset : next.entrySet()) {
                segment.add(offset.getKey() + "-" + offset.getValue());
            }
            if (next.size() > 0) {
                return String.join("_", segment);
            }
            return null;
        }
        public URIBuilder after(List<Record> records, URIBuilder uri) {
            if (records.size() == 0) {
                return URIBuilder.empty();
            }
            return uri.addParameter("after", pagination(records));
        }
        public URIBuilder before(List<Record> records, URIBuilder uri) {
            if (records.size() == 0) {
                return URIBuilder.empty();
            }
            return uri.addParameter("before", pagination(records));
        }
    }
    @ToString
    @EqualsAndHashCode(callSuper = true)
    @Getter
    @Setter
    public static class TailOptions extends BaseOptions {
        private List<String> topics;
        protected List<String> after;
        public TailOptions(String clusterId, List<String> topics) {
            this.clusterId = clusterId;
            this.topics = topics;
        }
    }
    @Data
    @Builder
    private static class OffsetBound {
        @With
        private final TopicPartition topicPartition;
        private final long begin;
    }
    @Data
    @Builder
    private static class EndOffsetBound {
        @With
        private final TopicPartition topicPartition;
        private final long begin;
        private final long end;
        private final KafkaConsumer<byte[], byte[]> consumer;
    }
    private void filterMessageLength(Record record) {
        if (maxKafkaMessageLength == Integer.MAX_VALUE || record.getValue() == null) {
            return;
        }
        int bytesLength = record.getValue().getBytes(StandardCharsets.UTF_8).length;
        if (bytesLength > maxKafkaMessageLength) {
            int substringChars = maxKafkaMessageLength / 1000;
            record.setValue(record.getValue().substring(0, substringChars));
            record.setTruncated(true);
        }
    }
}
package org.akhq.utils;
import io.micronaut.core.serialize.exceptions.SerializationException;
import lombok.extern.slf4j.Slf4j;
import org.akhq.configs.AvroTopicsMapping;
import org.akhq.configs.Connection;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
/**
 * Class for deserialization of messages in Avro raw data binary format using topics mapping config.
 */
@Slf4j
public class AvroToJsonDeserializer {
    private final DecoderFactory decoderFactory = DecoderFactory.get();
    private final Map<String, Schema> keySchemas;
    private final Map<String, Schema> valueSchemas;
    private final List<AvroTopicsMapping> topicsMapping;
    private final String avroSchemasFolder;
    private final AvroToJsonSerializer avroToJsonSerializer;
    public AvroToJsonDeserializer(Connection.Deserialization.AvroDeserializationTopicsMapping avroDeserializationTopicsMapping, AvroToJsonSerializer avroToJsonSerializer) {
        if (avroDeserializationTopicsMapping == null) {
            this.keySchemas = new HashMap<>();
            this.valueSchemas = new HashMap<>();
            this.topicsMapping = new ArrayList<>();
            this.avroSchemasFolder = null;
            this.avroToJsonSerializer = null;
        } else {
            this.avroSchemasFolder = avroDeserializationTopicsMapping.getSchemasFolder();
            this.topicsMapping = avroDeserializationTopicsMapping.getTopicsMapping();
            this.keySchemas = buildSchemas(AvroTopicsMapping::getKeySchemaFile);
            this.valueSchemas = buildSchemas(AvroTopicsMapping::getValueSchemaFile);
            this.avroToJsonSerializer = avroToJsonSerializer;
        }
    }
    /**
     * Load Avro schemas from schema folder
     *
     * @return map where keys are topic regexes and value is Avro schema
     */
    private Map<String, Schema> buildSchemas(Function<AvroTopicsMapping, String> schemaFileMapper) {
        Map<String, Schema> allSchemas = new HashMap<>();
        for (AvroTopicsMapping mapping : topicsMapping) {
            String schemaFile = schemaFileMapper.apply(mapping);
            if (schemaFile != null) {
                try {
                    Schema schema = loadSchemaFile(mapping, schemaFile);
                    allSchemas.put(mapping.getTopicRegex(), schema);
                } catch (IOException e) {
                    throw new RuntimeException(String.format("Cannot get a schema file for the topics regex [%s]", mapping.getTopicRegex()), e);
                }
            }
        }
        return allSchemas;
    }
    Schema loadSchemaFile(AvroTopicsMapping mapping, String schemaFile) throws IOException {
        if (avroSchemasFolder != null && Files.exists(Path.of(avroSchemasFolder))) {
            String fullPath = avroSchemasFolder + File.separator + schemaFile;
            return new Schema.Parser().parse(Path.of(fullPath).toFile());
        }
        throw new FileNotFoundException("Avro schema file is not found for topic regex [" +
            mapping.getTopicRegex() + "]. Folder is not specified or doesn't exist.");
    }
    /**
     * Deserialize from Avro raw data binary format to Json.
     * Messages must have been encoded directly with {@link org.apache.avro.io.DatumWriter}, not {@link org.apache.avro.file.DataFileWriter} or {@link org.apache.avro.message.BinaryMessageEncoder}.
     * Topic name should match topic-regex from {@code akhq.connections.[clusterName].deserialization.avro.topics-mapping} config
     * and schema should be set for key or value in that config.
     *
     * @param topic  current topic name
     * @param buffer binary data to decode
     * @param isKey  is this data represent key or value
     * @return {@code null} if cannot deserialize or configuration is not matching, return decoded string otherwise
     */
    public String deserialize(String topic, byte[] buffer, boolean isKey) {
        AvroTopicsMapping matchingConfig = findMatchingConfig(topic);
        if (matchingConfig == null) {
            log.debug("Avro deserialization config is not found for topic [{}]", topic);
            return null;
        }
        if (matchingConfig.getKeySchemaFile() == null && matchingConfig.getValueSchemaFile() == null) {
            throw new SerializationException(String.format("Avro deserialization is configured for topic [%s], " +
                    "but schema is not specified neither for a key, nor for a value.", topic));
        }
        Schema schema;
        if (isKey) {
            schema = keySchemas.get(matchingConfig.getTopicRegex());
        } else {
            schema = valueSchemas.get(matchingConfig.getTopicRegex());
        }
        if (schema == null) {
            return null;
        }
        String result;
        try {
            result = tryToDeserializeWithSchemaFile(buffer, schema);
        } catch (Exception e) {
            throw new SerializationException(String.format("Cannot deserialize message with Avro deserializer " +
                    "for topic [%s] and schema [%s]", topic, schema.getFullName()), e);
        }
        return result;
    }
    private AvroTopicsMapping findMatchingConfig(String topic) {
        for (AvroTopicsMapping mapping : topicsMapping) {
            if (topic.matches(mapping.getTopicRegex())) {
                return new AvroTopicsMapping(
                        mapping.getTopicRegex(),
                        mapping.getKeySchemaFile(), mapping.getValueSchemaFile());
            }
        }
        return null;
    }
    private String tryToDeserializeWithSchemaFile(byte[] buffer, Schema schema) throws IOException {
        DatumReader<?> reader = new GenericDatumReader<>(schema);
        Object result = reader.read(null, decoderFactory.binaryDecoder(buffer, null));
        //for primitive avro type
        if (!(result instanceof GenericRecord)) {
            return String.valueOf(result);
        }
        GenericRecord record = (GenericRecord) result;
        return avroToJsonSerializer.toJson(record);
    }
}
package org.akhq.utils;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import jakarta.inject.Singleton;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import org.apache.avro.generic.GenericRecord;
@Singleton
public class AvroToJsonSerializer {
    private final ObjectMapper mapper;
    public AvroToJsonSerializer(@Value("${akhq.avro-serializer.json.serialization.inclusions}") @Nullable List<Include> jsonInclusions) {
        List<Include> inclusions = jsonInclusions != null ? jsonInclusions : Collections.emptyList();
        this.mapper = createObjectMapper(inclusions);
    }
    private ObjectMapper createObjectMapper(List<Include> jsonInclusions) {
        ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .setTimeZone(TimeZone.getDefault());
        for (Include include : jsonInclusions) {
            objectMapper = objectMapper.setSerializationInclusion(include);
        }
        return objectMapper;
    }
    public String toJson(GenericRecord record) throws IOException {
        Map<String, Object> map = AvroDeserializer.recordDeserializer(record);
        return mapper.writeValueAsString(map);
    }
    public ObjectMapper getMapper() {
        return mapper;
    }
}
package org.akhq.models;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.TopicConfig;
import org.akhq.repositories.ConfigRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public class Topic {
    private String name;
    private boolean internal;
    @JsonIgnore
    private boolean configInternal;
    @JsonIgnore
    private boolean configStream;
    private final List<Partition> partitions = new ArrayList<>();
    public Topic(
        TopicDescription description,
        List<LogDir> logDirs,
        List<Partition.Offsets> offsets,
        boolean configInternal,
        boolean configStream
    ) {
        this.name = description.name();
        this.internal = description.isInternal();
        this.configInternal = configInternal;
        this.configStream = configStream;
        for (TopicPartitionInfo partition : description.partitions()) {
            this.partitions.add(new Partition(
                description.name(),
                partition,
                logDirs.stream()
                    .filter(logDir -> logDir.getPartition() == partition.partition())
                    .collect(Collectors.toList()),
                offsets.stream()
                    .filter(offset -> offset.getPartition() == partition.partition())
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException(
                        "Partition Offsets '" + partition.partition() + "' doesn't exist for topic " + this.name
                    ))
            ));
        }
    }
    public boolean isInternalTopic() {
        return this.internal || this.configInternal;
    }
    public boolean isStreamTopic() {
        return this.configStream;
    }
    public long getReplicaCount() {
        return this.getPartitions().stream()
            .mapToLong(partitions -> partitions.getNodes().size())
            .max()
            .orElseGet(() -> 0L);
    }
    public long getInSyncReplicaCount() {
        return this.getPartitions().stream()
            .mapToLong(partition -> partition.getNodes().stream().filter(node -> node.isInSyncReplicas()).count())
            .min()
            .orElseGet(() -> 0L);
    }
    public List<LogDir> getLogDir() {
        return this.getPartitions().stream()
            .flatMap(partition -> partition.getLogDir().stream())
            .collect(Collectors.toList());
    }
    public Long getLogDirSize() {
        Integer logDirCount = this.getPartitions().stream()
            .map(r -> r.getLogDir().size())
            .reduce(0, Integer::sum);
        if (logDirCount == 0) {
            return null;
        }
        return Optional
            .of(this.getPartitions().stream()
                .map(Partition::getLogDirSize)
                .reduce(0L, Long::sum)
            )
            .orElse(null);
    }
    public long getSize() {
        return this.getPartitions().stream()
            .map(partition -> partition.getLastOffset() - partition.getFirstOffset())
            .reduce(0L, Long::sum);
    }
    public long getSize(int partition) {
        for (Partition current : this.getPartitions()) {
            if (partition == current.getId()) {
                return current.getLastOffset() - current.getFirstOffset();
            }
        }
        throw new NoSuchElementException("Partition '" + partition + "' doesn't exist for topic " + this.name);
    }
    public Boolean canDeleteRecords(String clusterId, ConfigRepository configRepository) throws ExecutionException, InterruptedException {
        if (this.isInternalTopic()) {
            return false;
        }
        return isCompacted(configRepository.findByTopic(clusterId, this.getName()));
    }
    public static boolean isCompacted(List<Config> configs) {
        return configs != null && configs
            .stream()
            .filter(config -> config.getName().equals(TopicConfig.CLEANUP_POLICY_CONFIG))
            .anyMatch(config -> config.getValue().contains(TopicConfig.CLEANUP_POLICY_COMPACT));
    }
}
package org.akhq.utils;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.akhq.configs.DataMasking;
import org.akhq.configs.RegexFilter;
import org.akhq.models.Record;
import java.util.List;
@Singleton
// For backwards compatibility and not wanting to break things for existing users, this is the default masker.
@Requires(property = "akhq.security.data-masking.mode", value = "regex", defaultValue = "regex")
public class RegexMasker implements Masker {
    private final List<RegexFilter> filters;
    public RegexMasker(DataMasking dataMasking) {
        this.filters = dataMasking.getFilters();
    }
    @Override
    public Record maskRecord(Record record) {
        if(filters.isEmpty()){
            return record;
        }
        String value = record.getValue();
        String key = record.getKey();
        for (RegexFilter filter : filters) {
            if (value != null) {
                value = value.replaceAll(filter.getSearchRegex(), filter.getReplacement());
            }
            if (key != null) {
                key = key.replaceAll(filter.getSearchRegex(), filter.getReplacement());
            }
        }
        record.setValue(value);
        record.setKey(key);
        return record;
    }
}
package org.akhq.controllers;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.akhq.configs.security.Role;
import org.akhq.models.Record;
import org.akhq.repositories.RecordRepository;
import org.akhq.security.annotation.AKHQSecured;
import org.reactivestreams.Publisher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import jakarta.inject.Inject;
@AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.READ)
@Controller
public class TailController extends AbstractController {
    private final RecordRepository recordRepository;
    @Inject
    public TailController(RecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.READ)
    @Get(value = "api/{cluster}/tail/sse", produces = MediaType.TEXT_EVENT_STREAM)
    @ExecuteOn(TaskExecutors.IO)
    @Operation(tags = {"topic data"}, summary = "Tail for data on multiple topic")
    public Publisher<Event<TailRecord>> sse(
        String cluster,
        List<String> topics,
        Optional<String> search,
        Optional<List<String>> after
    ) {
        checkIfClusterAndResourceAllowed(cluster, topics);
        RecordRepository.TailOptions options = new RecordRepository.TailOptions(cluster, topics);
        search.ifPresent(options::setSearch);
        after.ifPresent(options::setAfter);
        return recordRepository
            .tail(cluster, options)
            .map(event -> {
                TailRecord tailRecord = new TailRecord();
                tailRecord.offsets = getOffsets(event);
                if (event.getData().getRecords().size() > 0) {
                    tailRecord.records = event.getData().getRecords();
                }
                return Event
                    .of(tailRecord)
                    .name(event.getName());
            });
    }
    private static List<String> getOffsets(Event<RecordRepository.TailEvent> event) {
        return event.getData()
            .getOffsets()
            .entrySet()
            .stream()
            .flatMap(s -> s.getKey()
                .entrySet()
                .stream()
                .map(r -> {
                    List<String> strings = Arrays.asList(
                        r.getKey(),
                        String.valueOf(r.getValue()),
                        String.valueOf(s.getValue() + 1)
                    );
                    return String.join(
                        ",",
                        strings
                    );
                })
            )
            .collect(Collectors.toList());
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    public static class TailRecord {
        @JsonProperty("records")
        private List<Record> records;
        @JsonProperty("offsets")
        private List<String> offsets = new ArrayList<>();
    }
}
package org.akhq.controllers;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableMap;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.reactivex.schedulers.Schedulers;
import io.swagger.v3.oas.annotations.Operation;
import lombok.*;
import org.akhq.configs.security.Role;
import org.akhq.models.*;
import org.akhq.modules.AbstractKafkaWrapper;
import org.akhq.repositories.*;
import org.akhq.security.annotation.AKHQSecured;
import org.akhq.utils.Pagination;
import org.akhq.utils.ResultNextList;
import org.akhq.utils.ResultPagedList;
import org.akhq.utils.TopicDataResultNextList;
import org.apache.kafka.common.resource.ResourceType;
import org.codehaus.httpcache4j.uri.URIBuilder;
import org.reactivestreams.Publisher;
import org.akhq.models.Record;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import jakarta.inject.Inject;
@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller
public class TopicController extends AbstractController {
    public static final String VALUE_SUFFIX = "-value";
    public static final String KEY_SUFFIX = "-key";
    @Inject
    private AbstractKafkaWrapper kafkaWrapper;
    @Inject
    private TopicRepository topicRepository;
    @Inject
    private ConfigRepository configRepository;
    @Inject
    private RecordRepository recordRepository;
    @Inject
    private ConsumerGroupRepository consumerGroupRepository;
    @Inject
    private Environment environment;
    @Inject
    private AccessControlListRepository aclRepository;
    @Inject
    private SchemaRegistryRepository schemaRegistryRepository;
    @Value("${akhq.topic.replication}")
    private Short replicationFactor;
    @Value("${akhq.topic.partition}")
    private Integer partitionCount;
    @Value("${akhq.topic.retention}")
    private Integer retention;
    @Value("${akhq.pagination.page-size}")
    private Integer pageSize;
    @Get ("api/topic/defaults-configs")
    @Operation(tags = {"topic"}, summary = "Get default topic configuration")
    public Map<String,Integer> getDefaultConf(){
        return Map.of(
            "replication", replicationFactor.intValue(),
            "partition", partitionCount,
            "retention", retention
        );
    }
    @Get("api/{cluster}/topic")
    @AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.READ)
    @Operation(tags = {"topic"}, summary = "List all topics")
    public ResultPagedList<Topic> list(
        HttpRequest<?> request,
        String cluster,
        Optional<String> search,
        Optional<TopicRepository.TopicListView> show,
        Optional<Integer> page,
        Optional<Integer> uiPageSize
    ) throws ExecutionException, InterruptedException {
        checkIfClusterAllowed(cluster);
        URIBuilder uri = URIBuilder.fromURI(request.getUri());
        Pagination pagination = new Pagination(uiPageSize.orElse(pageSize), uri, page.orElse(1));
        return ResultPagedList.of(this.topicRepository.list(
            cluster,
            pagination,
            show.orElse(TopicRepository.TopicListView.HIDE_INTERNAL),
            search,
            buildUserBasedResourceFilters(cluster)
        ));
    }
    @AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.READ)
    @Get("api/{cluster}/topic/name")
    @Operation(tags = {"topic"}, summary = "List all topics name")
    public List<String> listTopicNames(
            HttpRequest<?> request,
            String cluster,
            Optional<TopicRepository.TopicListView> show
    ) throws ExecutionException, InterruptedException {
        checkIfClusterAllowed(cluster);
        return this.topicRepository.all(cluster,
            show.orElse(TopicRepository.TopicListView.HIDE_INTERNAL),
            Optional.empty(),
            buildUserBasedResourceFilters(cluster));
    }
    @AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.CREATE)
    @Post(value = "api/{cluster}/topic")
    @Operation(tags = {"topic"}, summary = "Create a topic")
    public Topic create(
        String cluster,
        String name,
        Optional<Integer> partition,
        Optional<Short> replication,
        Map<String, String> configs
    ) throws Throwable {
        checkIfClusterAndResourceAllowed(cluster, name);
        this.topicRepository.create(
            cluster,
            name,
            partition.orElse(this.partitionCount) ,
            replication.orElse(this.replicationFactor),
            (configs != null ? configs : ImmutableMap.<String, String>of())
                .entrySet()
                .stream()
                .map(r -> new Config(r.getKey(), r.getValue()))
                .collect(Collectors.toList())
        );
        return this.topicRepository.findByName(cluster, name);
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.CREATE)
    @Post(value = "api/{cluster}/topic/{topicName}/data")
    @Operation(tags = {"topic data"}, summary = "Produce data to a topic")
    public List<Record> produce(
        HttpRequest<?> request,
        String cluster,
        String topicName,
        Optional<String> value,
        Optional<String> key,
        Optional<Integer> partition,
        Optional<String> timestamp,
        List<KeyValue<String, String>> headers,
        Optional<String> keySchema,
        Optional<String> valueSchema,
        Boolean multiMessage,
        Optional<String> keyValueSeparator
    ) throws ExecutionException, InterruptedException, RestClientException, IOException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        Topic targetTopic = topicRepository.findByName(cluster, topicName);
        return
            this.recordRepository.produce(
                cluster,
                topicName,
                value,
                headers,
                key,
                partition,
                timestamp.map(r -> Instant.parse(r).toEpochMilli()),
                keySchema,
                valueSchema,
                multiMessage,
                keyValueSeparator).stream()
                    .map(recordMetadata -> new Record(recordMetadata,
                            schemaRegistryRepository.getSchemaRegistryType(cluster),
                            key.map(String::getBytes).orElse(null),
                            value.map(String::getBytes).orElse(null),
                            headers,
                            targetTopic, null))
                    .collect(Collectors.toList());
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.READ)
    @Get("api/{cluster}/topic/{topicName}/data")
    @Operation(tags = {"topic data"}, summary = "Read datas from a topic")
    public ResultNextList<Record> data(
        HttpRequest<?> request,
        String cluster,
        String topicName,
        Optional<String> after,
        Optional<Integer> partition,
        Optional<RecordRepository.Options.Sort> sort,
        Optional<String> timestamp,
        Optional<String> endTimestamp,
        Optional<String> searchByKey,
        Optional<String> searchByValue,
        Optional<String> searchByHeaderKey,
        Optional<String> searchByHeaderValue,
        Optional<String> searchByKeySubject,
        Optional<String> searchByValueSubject
    ) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        Topic topic = this.topicRepository.findByName(cluster, topicName);
        RecordRepository.Options options =
                dataSearchOptions(cluster,
                        topicName,
                        after,
                        partition,
                        sort,
                        timestamp,
                        endTimestamp,
                        searchByKey,
                        searchByValue,
                        searchByHeaderKey,
                        searchByHeaderValue,
                        searchByKeySubject,
                        searchByValueSubject);
        URIBuilder uri = URIBuilder.fromURI(request.getUri());
        List<Record> data = this.recordRepository.consume(cluster, options);
        return TopicDataResultNextList.of(
            data,
            options.after(data, uri),
            (options.getPartition() == null ? topic.getSize() : topic.getSize(options.getPartition())),
            topic.canDeleteRecords(cluster, configRepository)
        );
    }
    @AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.READ)
    @Get("api/{cluster}/topic/{topicName}")
    @Operation(tags = {"topic"}, summary = "Retrieve a topic")
    public Topic home(String cluster, String topicName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        return this.topicRepository.findByName(cluster, topicName);
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.READ)
    @Get("api/{cluster}/topic/last-record")
    @Operation(tags = {"topic"}, summary = "Retrieve the last record for a list of topics")
    public Map<String, Record> lastRecord(String cluster, List<String> topics) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topics);
        return this.recordRepository.getLastRecord(cluster, topics);
    }
    @AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.READ)
    @Get("api/{cluster}/topic/{topicName}/partitions")
    @Operation(tags = {"topic"}, summary = "List all partition from a topic")
    public List<Partition> partitions(String cluster, String topicName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        return this.topicRepository.findByName(cluster, topicName).getPartitions();
    }
    @AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.READ)
    @Get("api/{cluster}/topic/{topicName}/groups")
    @Operation(tags = {"topic"}, summary = "List all consumer groups from a topic")
    public List<ConsumerGroup> groups(String cluster, String topicName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        return this.consumerGroupRepository.findByTopic(cluster, topicName,
            buildUserBasedResourceFilters(cluster));
    }
    @AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.READ_CONFIG)
    @Get("api/{cluster}/topic/{topicName}/configs")
    @Operation(tags = {"topic"}, summary = "List all configs from a topic")
    public List<Config> config(String cluster, String topicName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        return this.configRepository.findByTopic(cluster, topicName);
    }
    @AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.READ)
    @Get("api/{cluster}/topic/{topicName}/logs")
    @Operation(tags = {"topic"}, summary = "List all logs from a topic")
    public List<LogDir> logs(String cluster, String topicName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        return this.topicRepository.findByName(cluster, topicName).getLogDir();
    }
    @AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.READ)
    @Get("api/{cluster}/topic/{topicName}/acls")
    @Operation(tags = {"topic"}, summary = "List all acls from a topic")
    public List<AccessControl> acls(String cluster, String topicName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        return aclRepository.findByResourceType(cluster, ResourceType.TOPIC, topicName);
    }
    @AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.ALTER_CONFIG)
    @Post(value = "api/{cluster}/topic/{topicName}/configs")
    @Operation(tags = {"topic"}, summary = "Update configs from a topic")
    public List<Config> updateConfig(String cluster, String topicName, @Body("configs") Map<String, String> configs) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        List<Config> updated = ConfigRepository.updatedConfigs(configs, this.configRepository.findByTopic(cluster, topicName), false);
        if (updated.size() == 0) {
            throw new IllegalArgumentException("No config to update");
        }
        this.configRepository.updateTopic(
            cluster,
            topicName,
            updated
        );
        return updated;
    }
    @AKHQSecured(resource =  Role.Resource.TOPIC, action =  Role.Action.UPDATE)
    @Post(value = "api/{cluster}/topic/{topicName}/partitions")
    @Operation(tags = {"topic"}, summary = "Increase partition for a topic")
    public HttpResponse<?> increasePartition(String cluster, String topicName, @Body Map<String, Integer> config) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        this.topicRepository.increasePartition(cluster, topicName, config.get("partition"));
        return HttpResponse.accepted();
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.DELETE)
    @Delete("api/{cluster}/topic/{topicName}/data/empty")
    @Operation(tags = {"topic data"}, summary = "Empty data from a topic")
    public HttpResponse<?> emptyTopic(String cluster, String topicName) throws ExecutionException, InterruptedException{
        checkIfClusterAndResourceAllowed(cluster, topicName);
        this.recordRepository.emptyTopic(
                cluster,
                topicName
        );
        return HttpResponse.noContent();
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.DELETE)
    @Delete("api/{cluster}/topic/{topicName}/data")
    @Operation(tags = {"topic data"}, summary = "Delete data from a topic by key")
    public Record deleteRecordApi(String cluster, String topicName, Integer partition, String key) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        return new Record(
            this.recordRepository.delete(
                cluster,
                topicName,
                partition,
                Base64.getDecoder().decode(key)
            ),
            schemaRegistryRepository.getSchemaRegistryType(cluster),
            Base64.getDecoder().decode(key),
            null,
            new ArrayList<>(),
            topicRepository.findByName(cluster, topicName),
            null
        );
    }
    @AKHQSecured(resource = Role.Resource.TOPIC, action = Role.Action.DELETE)
    @Delete("api/{cluster}/topic/{topicName}")
    @Operation(tags = {"topic"}, summary = "Delete a topic")
    public HttpResponse<?> delete(String cluster, String topicName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        this.kafkaWrapper.deleteTopics(cluster, topicName);
        return HttpResponse.noContent();
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.READ)
    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "api/{cluster}/topic/{topicName}/data/search", produces = MediaType.TEXT_EVENT_STREAM)
    @Operation(tags = {"topic data"}, summary = "Search for data for a topic")
    public Publisher<Event<SearchRecord>> sse(
        String cluster,
        String topicName,
        Optional<String> after,
        Optional<Integer> partition,
        Optional<RecordRepository.Options.Sort> sort,
        Optional<String> timestamp,
        Optional<String> endTimestamp,
        Optional<String> searchByKey,
        Optional<String> searchByValue,
        Optional<String> searchByHeaderKey,
        Optional<String> searchByHeaderValue,
        Optional<String> searchByKeySubject,
        Optional<String> searchByValueSubject
    ) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        RecordRepository.Options options = dataSearchOptions(
            cluster,
            topicName,
            after,
            partition,
            sort,
            timestamp,
            endTimestamp,
            searchByKey,
            searchByValue,
            searchByHeaderKey,
            searchByHeaderValue,
            searchByKeySubject,
            searchByValueSubject
        );
        Topic topic = topicRepository.findByName(cluster, topicName);
        return recordRepository
            .search(topic, options)
            .map(event -> {
                SearchRecord searchRecord = new SearchRecord(
                    event.getData().getPercent(),
                    event.getData().getAfter()
                );
                if (event.getData().getRecords().size() > 0) {
                    searchRecord.records = event.getData().getRecords();
                }
                return Event
                    .of(searchRecord)
                    .name(event.getName());
            });
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.READ)
    @ExecuteOn(TaskExecutors.IO)
    @Get(value = "api/{cluster}/topic/{topicName}/data/download")
    @Operation(tags = {"topic data download"}, summary = "Download data for a topic")
    public HttpResponse<StreamedFile> download(
        String cluster,
        String topicName,
        Optional<String> after,
        Optional<Integer> partition,
        Optional<RecordRepository.Options.Sort> sort,
        Optional<String> timestamp,
        Optional<String> endTimestamp,
        Optional<String> searchByKey,
        Optional<String> searchByValue,
        Optional<String> searchByHeaderKey,
        Optional<String> searchByHeaderValue,
        Optional<String> searchByKeySubject,
        Optional<String> searchByValueSubject
    ) throws ExecutionException, InterruptedException, IOException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        RecordRepository.Options options = dataSearchOptions(
            cluster,
            topicName,
            after,
            partition,
            sort,
            timestamp,
            endTimestamp,
            searchByKey,
            searchByValue,
            searchByHeaderKey,
            searchByHeaderValue,
            searchByKeySubject,
            searchByValueSubject
        );
        // Set in MAX_POLL_RECORDS_CONFIG, big number increases speed
        options.setSize(10000);
        Topic topic = topicRepository.findByName(cluster, topicName);
        ObjectMapper mapper = JsonMapper.builder()
            .configure(MapperFeature.DEFAULT_VIEW_INCLUSION, false)
            .build();
        // For ZonedDatetime serialization
        mapper.findAndRegisterModules();
        mapper.setConfig(mapper.getSerializationConfig().withView(Record.Views.Download.class));
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out);
        new Thread(() -> {
            try (out) {
                out.write('[');
                AtomicBoolean continueSearch = new AtomicBoolean(true);
                AtomicBoolean isFirstBatch = new AtomicBoolean(true);
                while(continueSearch.get()) {
                    recordRepository
                        .search(topic, options)
                        .observeOn(Schedulers.io())
                        .map(event -> {
                            if (!event.getData().getRecords().isEmpty()) {
                                if (!isFirstBatch.getAndSet(false)) {
                                    // Add a comma between batches records
                                    out.write(',');
                                }
                                byte[] bytes = mapper.writeValueAsString(event.getData().getRecords()).getBytes();
                                // Remove start [ and end ] to concatenate records in the same array
                                out.write(Arrays.copyOfRange(bytes, 1, bytes.length - 1));
                            } else {
                                // No more records, add the end array ] and stop here
                                if (event.getData().getEmptyPoll() == 1) {
                                    out.write(']');
                                    out.flush();
                                    continueSearch.set(false);
                                }
                                else if (event.getData().getAfter() != null) {
                                    // Continue to search from the last offsets
                                    options.setAfter(event.getData().getAfter());
                                }
                            }
                            return 0;
                        }).blockingSubscribe();
                }
            } catch (IOException | ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).start();
        return HttpResponse.ok(new StreamedFile(in, MediaType.APPLICATION_JSON_TYPE));
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.READ)
    @Get("api/{cluster}/topic/{topicName}/data/record/{partition}/{offset}")
    @Operation(tags = {"topic data"}, summary = "Get a single record by partition and offset")
    public ResultNextList<Record> record(
            HttpRequest<?> request,
            String cluster,
            String topicName,
            Integer partition,
            Long offset
    ) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        Topic topic = this.topicRepository.findByName(cluster, topicName);
        // after wait for next offset, so add - 1 to allow to have the current offset
        RecordRepository.Options options = dataSearchOptions(
            cluster,
            topicName,
            offset - 1 < 0 ? Optional.empty() : Optional.of(String.join("-", String.valueOf(partition), String.valueOf(offset - 1))),
            Optional.of(partition),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
        Optional<Record> singleRecord = this.recordRepository.consumeSingleRecord(cluster, topic, options);
        List<Record> data = singleRecord.map(Collections::singletonList).orElse(Collections.emptyList());
        return TopicDataResultNextList.of(
                data,
                URIBuilder.empty(),
                data.size(),
                topic.canDeleteRecords(cluster, configRepository)
        );
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.READ)
    @Get("api/{cluster}/topic/{topicName}/offsets/start")
    @Operation(tags = {"topic data"}, summary = "Get topic partition offsets by timestamp")
    public List<RecordRepository.TimeOffset> offsetsStart(String cluster, String topicName, Optional<Instant> timestamp) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, topicName);
        Topic topic = this.topicRepository.findByName(cluster, topicName);
        return recordRepository.getOffsetForTime(
                cluster,
                topic.getPartitions()
                        .stream()
                        .map(r -> new TopicPartition(r.getTopic(), r.getId()))
                        .collect(Collectors.toList()),
                timestamp.orElse(Instant.now()).toEpochMilli()
        );
    }
    @AKHQSecured(resource = Role.Resource.TOPIC_DATA, action = Role.Action.CREATE)
    @Post("api/{fromCluster}/topic/{fromTopicName}/copy/{toCluster}/topic/{toTopicName}")
    @Operation(tags = {"topic data"}, summary = "Copy from a topic to another topic")
    public RecordRepository.CopyResult copy(
            HttpRequest<?> request,
            String fromCluster,
            String fromTopicName,
            String toCluster,
            String toTopicName,
            @Body List<OffsetCopy> offsets
    ) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(fromCluster, fromTopicName);
        checkIfClusterAndResourceAllowed(toCluster, toTopicName);
        Topic fromTopic = this.topicRepository.findByName(fromCluster, fromTopicName);
        Topic toTopic = this.topicRepository.findByName(toCluster, toTopicName);
        if (!CollectionUtils.isNotEmpty(offsets)) {
            throw new IllegalArgumentException("Empty collections");
        }
        if (fromCluster.equals(toCluster) && fromTopicName.equals(toTopicName)) {
            // #745 Prevent endless loop when copying topic onto itself; Use intermediate copy topic for duplication
            throw new IllegalArgumentException("Can not copy topic to itself");
        }
        // after wait for next offset, so add - 1 to allow to have the current offset
        String offsetsList = offsets.stream()
            .filter(offsetCopy -> offsetCopy.offset - 1 >= 0)
            .map(offsetCopy ->
                String.join("-", String.valueOf(offsetCopy.partition), String.valueOf(offsetCopy.offset - 1)))
            .collect(Collectors.joining("_"));
        RecordRepository.Options options = dataSearchOptions(
            fromCluster,
            fromTopicName,
            Optional.ofNullable(StringUtils.isNotEmpty(offsetsList) ? offsetsList : null),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
        return this.recordRepository.copy(fromTopic, toCluster, toTopic, offsets, options);
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    public static class CopyResponse {
        int records;
    }
    private RecordRepository.Options dataSearchOptions(
        String cluster,
        String topicName,
        Optional<String> after,
        Optional<Integer> partition,
        Optional<RecordRepository.Options.Sort> sort,
        Optional<String> timestamp,
        Optional<String> endTimestamp,
        Optional<String> searchByKey,
        Optional<String> searchByValue,
        Optional<String> searchByHeaderKey,
        Optional<String> searchByHeaderValue,
        Optional<String> searchByKeySubject,
        Optional<String> searchByValueSubject
    ) {
        RecordRepository.Options options = new RecordRepository.Options(environment, cluster, topicName);
        after.ifPresent(options::setAfter);
        partition.ifPresent(options::setPartition);
        sort.ifPresent(options::setSort);
        timestamp.map(r -> Instant.parse(r).toEpochMilli()).ifPresent(options::setTimestamp);
        endTimestamp.map(r -> Instant.parse(r).toEpochMilli()).ifPresent(options::setEndTimestamp);
        after.ifPresent(options::setAfter);
        searchByKey.ifPresent(options::setSearchByKey);
        searchByValue.ifPresent(options::setSearchByValue);
        searchByHeaderKey.ifPresent(options::setSearchByHeaderKey);
        searchByHeaderValue.ifPresent(options::setSearchByHeaderValue);
        searchByKeySubject.ifPresent(options::setSearchByKeySubject);
        searchByValueSubject.ifPresent(options::setSearchByValueSubject);
        return options;
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    public static class SearchRecord {
        public SearchRecord(double percent, String after) {
            this.percent = percent;
            this.after = after;
        }
        @JsonProperty("percent")
        private final Double percent;
        @JsonProperty("records")
        private List<Record> records;
        @JsonProperty("after")
        private final String after;
    }
    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    public static class OffsetCopy {
        private int partition;
        private long offset;
    }
}
package org.akhq.utils;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;
import org.akhq.configs.Connection;
import org.akhq.configs.TopicsMapping;
import org.apache.kafka.common.errors.SerializationException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
/**
 * Class for deserialization of messages in Protobuf format using topics mapping config.
 */
@Slf4j
public class ProtobufToJsonDeserializer {
    private final Map<String, List<Descriptor>> descriptors;
    private final List<TopicsMapping> topicsMapping;
    private final String protobufDescriptorsFolder;
    public ProtobufToJsonDeserializer(Connection.Deserialization.ProtobufDeserializationTopicsMapping protobufDeserializationTopicsMapping) {
        if (protobufDeserializationTopicsMapping == null) {
            this.descriptors = new HashMap<>();
            this.topicsMapping = new ArrayList<>();
            this.protobufDescriptorsFolder = null;
        } else {
            this.protobufDescriptorsFolder = protobufDeserializationTopicsMapping.getDescriptorsFolder();
            this.topicsMapping = protobufDeserializationTopicsMapping.getTopicsMapping();
            this.descriptors = buildAllDescriptors();
        }
    }
    /**
     * Check Protobuf deserialization topics mapping config, get all Protobuf descriptor files
     * from Protobuf descriptor folder or descriptor files in Base64 format and convert to bytes.
     * For each descriptor file builds Descriptors list - full description with all dependencies.
     *
     * @return map where keys are topic regexes and values are Descriptors matching these regexes
     */
    private Map<String, List<Descriptor>> buildAllDescriptors() {
        Map<String, List<Descriptor>> allDescriptors = new HashMap<>();
        for (TopicsMapping mapping : topicsMapping) {
            byte[] fileBytes = new byte[0];
            try {
                fileBytes = getDescriptorFileAsBytes(mapping);
            } catch (IOException e) {
                throw new RuntimeException(String.format("Cannot get a descriptor file for the topics regex [%s]", mapping.getTopicRegex()), e);
            }
            try {
                allDescriptors.put(mapping.getTopicRegex(), buildAllDescriptorsForDescriptorFile(fileBytes));
            } catch (IOException | DescriptorValidationException e) {
                throw new RuntimeException(String.format("Cannot build Protobuf descriptors for the topics regex [%s]", mapping.getTopicRegex()), e);
            }
        }
        return allDescriptors;
    }
    byte[] getDescriptorFileAsBytes(TopicsMapping mapping) throws IOException {
        if (protobufDescriptorsFolder != null && Files.exists(Path.of(protobufDescriptorsFolder))) {
            String descriptorFile = mapping.getDescriptorFile();
            if (descriptorFile != null) {
                String fullPath = protobufDescriptorsFolder + File.separator + descriptorFile;
                return Files.readAllBytes(Path.of(fullPath));
            }
        }
        String descriptorFileBase64 = mapping.getDescriptorFileBase64();
        if (descriptorFileBase64 != null) {
            return Base64.getDecoder().decode(descriptorFileBase64);
        }
        throw new FileNotFoundException("Protobuf descriptor file is not found for topic regex [" +
                mapping.getTopicRegex() + "]. File name or Base64 file content is not specified.");
    }
    /**
     * Builds Descriptors list for current descriptor file
     */
    private List<Descriptor> buildAllDescriptorsForDescriptorFile(byte[] descriptorFile)
            throws IOException, DescriptorValidationException {
        FileDescriptorSet fileDescriptorSet = FileDescriptorSet.parseFrom(descriptorFile);
        List<FileDescriptor> fileDescriptorsWithDependencies = new ArrayList<>();
        for (FileDescriptorProto protoDescriptorFile : fileDescriptorSet.getFileList()) {
            FileDescriptor fd = FileDescriptor.buildFrom(protoDescriptorFile,
                    fileDescriptorsWithDependencies.toArray(new FileDescriptor[fileDescriptorsWithDependencies.size()]));
            fileDescriptorsWithDependencies.add(fd);
        }
        return fileDescriptorsWithDependencies
                .stream().flatMap(desc -> desc.getMessageTypes().stream())
                .collect(Collectors.toList());
    }
    /**
     * Deserialize binary data from Protobuf format to Json.
     * Topic name should match topic-regex from {@code akhq.connections.[clusterName].deserialization.protobuf.topics-mapping} config
     * and message-type should be set for key or value in that config.
     *
     * @param topic  current topic name
     * @param buffer binary data to decode
     * @param isKey  is this data represent key or value
     * @return {@code null} if cannot deserialize or configuration is not matching, return decoded string otherwise
     */
    public String deserialize(String topic, byte[] buffer, boolean isKey) {
        TopicsMapping matchingConfig = findMatchingConfig(topic);
        if (matchingConfig == null) {
            log.debug("Protobuf deserialization config is not found for topic [{}]", topic);
            return null;
        }
        if (matchingConfig.getValueMessageType() == null && matchingConfig.getKeyMessageType() == null) {
            throw new SerializationException(String.format("Protobuf deserialization is configured for topic [%s], " +
                    "but message type is not specified neither for a key, nor for a value.", topic));
        }
        String messageType = matchingConfig.getValueMessageType();
        if (isKey) {
            messageType = matchingConfig.getKeyMessageType();
        }
        if (messageType == null) {
            return null;
        }
        String result;
        try {
            result = tryToDeserializeWithMessageType(buffer, matchingConfig.getTopicRegex(), messageType);
        } catch (Exception e) {
            throw new SerializationException(String.format("Cannot deserialize message with Protobuf deserializer " +
                    "for topic [%s] and message type [%s]", topic, messageType), e);
        }
        return result;
    }
    private TopicsMapping findMatchingConfig(String topic) {
        for (TopicsMapping mapping : topicsMapping) {
            if (topic.matches(mapping.getTopicRegex())) {
                return new TopicsMapping(
                        mapping.getTopicRegex(),
                        mapping.getDescriptorFile(), mapping.getDescriptorFileBase64(),
                        mapping.getKeyMessageType(), mapping.getValueMessageType());
            }
        }
        return null;
    }
    private String tryToDeserializeWithMessageType(byte[] buffer, String topicRegex, String messageType) throws IOException {
        List<Descriptor> descriptorsWithDependencies = this.descriptors.get(topicRegex);
        List<Descriptor> descriptorsForConfiguredMessageTypes =
                descriptorsWithDependencies.stream()
                        .filter(mp -> messageType.equals(mp.getFullName()))
                        .collect(Collectors.toList());
        if (descriptorsForConfiguredMessageTypes.isEmpty()) {
            throw new SerializationException(String.format("Not found descriptors for topic regex [%s] " +
                    "and message type [%s]", topicRegex, messageType));
        }
        for (Descriptor descriptor : descriptorsForConfiguredMessageTypes) {
            String decodedMessage = tryToParseDataToJsonWithDescriptor(buffer, descriptor, descriptorsWithDependencies);
            if (!decodedMessage.isEmpty()) {
                return decodedMessage;
            }
        }
        return null;
    }
    private String tryToParseDataToJsonWithDescriptor(byte[] buffer, Descriptor descriptor, List<Descriptor> allDependencies) throws IOException {
        DynamicMessage message = DynamicMessage.parseFrom(descriptor, buffer);
        JsonFormat.TypeRegistry typeRegistry = JsonFormat.TypeRegistry.newBuilder().add(allDependencies).build();
        JsonFormat.Printer printer = JsonFormat.printer().usingTypeRegistry(typeRegistry);
        return printer.print(message);
    }
}
package org.akhq.utils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.SneakyThrows;
import org.akhq.configs.DataMasking;
import org.akhq.configs.JsonMaskingFilter;
import org.akhq.models.Record;
import java.util.List;
@Singleton
@Requires(property = "akhq.security.data-masking.mode", value = "json_show_by_default")
public class JsonShowByDefaultMasker implements Masker {
    private final List<JsonMaskingFilter> jsonMaskingFilters;
    private final String jsonMaskReplacement;
    public JsonShowByDefaultMasker(DataMasking dataMasking) {
        this.jsonMaskingFilters = dataMasking.getJsonFilters();
        this.jsonMaskReplacement = dataMasking.getJsonMaskReplacement();
    }
    public Record maskRecord(Record record) {
        try {
            if(isJson(record)) {
                return jsonMaskingFilters
                    .stream()
                    .filter(jsonMaskingFilter -> record.getTopic().getName().equalsIgnoreCase(jsonMaskingFilter.getTopic()))
                    .findFirst()
                    .map(filter -> applyMasking(record, filter.getKeys()))
                    .orElse(record);
            }
        } catch (Exception e) {
            LOG.error("Error masking record", e);
        }
        return record;
    }
    @SneakyThrows
    private Record applyMasking(Record record, List<String> keys) {
        JsonObject jsonElement = JsonParser.parseString(record.getValue()).getAsJsonObject();
        for(String key : keys) {
            maskField(jsonElement, key.split("\\."), 0);
        }
        record.setValue(jsonElement.toString());
        return record;
    }
    private void maskField(JsonObject node, String[] keys, int index) {
        if (index == keys.length - 1) {
            if (node.has(keys[index])) {
                node.addProperty(keys[index], jsonMaskReplacement);
            }
        } else {
            JsonElement childNode = node.get(keys[index]);
            if (childNode != null && childNode.isJsonObject()) {
                maskField(childNode.getAsJsonObject(), keys, index + 1);
            }
        }
    }
}