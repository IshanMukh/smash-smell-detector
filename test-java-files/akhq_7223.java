package org.akhq.repositories;
import org.akhq.models.Cluster;
import org.akhq.modules.AbstractKafkaWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.ExecutionException;
@Singleton
public class ClusterRepository extends AbstractRepository {
    @Inject
    AbstractKafkaWrapper kafkaWrapper;
    public Cluster get(String clusterId) throws ExecutionException, InterruptedException {
        return new Cluster(kafkaWrapper.describeCluster(clusterId));
    }
}
package org.akhq.modules;
import com.google.common.collect.ImmutableMap;
import org.akhq.models.Partition;
import org.akhq.models.audit.ConsumerGroupAuditEvent;
import org.akhq.models.audit.TopicAuditEvent;
import org.akhq.utils.Logger;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.SecurityDisabledException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import jakarta.inject.Inject;
import static java.util.stream.Collectors.*;
abstract public class AbstractKafkaWrapper {
    @Inject
    private KafkaModule kafkaModule;
    @Inject
    private AuditModule auditModule;
    private final Map<String, DescribeClusterResult> cluster = new HashMap<>();
    public DescribeClusterResult describeCluster(String clusterId) throws ExecutionException {
        if (!this.cluster.containsKey(clusterId)) {
            DescribeClusterResult cluster = kafkaModule.getAdminClient(clusterId).describeCluster();
            Logger.call(cluster.clusterId(), "Get cluster");
            Logger.call(cluster.nodes() , "Get nodes");
            Logger.call(cluster.controller() , "Get contoller");
            return cluster;
        }
        return this.cluster.get(clusterId);
    }
    private Map<String, Collection<TopicListing>> listTopics = new HashMap<>();
    public Collection<TopicListing> listTopics(String clusterId) throws ExecutionException {
        if (!this.listTopics.containsKey(clusterId)) {
            this.listTopics.put(clusterId, Logger.call(
                kafkaModule.getAdminClient(clusterId).listTopics(
                    new ListTopicsOptions().listInternal(true)
                ).listings(),
                "List topics"
            ));
        }
        return this.listTopics.get(clusterId);
    }
    private final Map<String, Map<String, TopicDescription>> describeTopics = new HashMap<>();
    public Map<String, TopicDescription> describeTopics(String clusterId, List<String> topics) throws ExecutionException {
        describeTopics.computeIfAbsent(clusterId, s -> new HashMap<>());
        List<String> list = new ArrayList<>(topics);
        list.removeIf(value -> this.describeTopics.get(clusterId).containsKey(value));
        if (list.size() > 0) {
            Map<String, TopicDescription> description = Logger.call(
                kafkaModule.getAdminClient(clusterId)
                    .describeTopics(list)
                    .all(),
                "Describe Topics {}",
                topics
            );
            this.describeTopics.get(clusterId).putAll(description);
        }
        return this.describeTopics
            .get(clusterId)
            .entrySet()
            .stream()
            .filter(e -> topics.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    public void createTopics(String clusterId, String name, int partitions, short replicationFactor,
        List<org.akhq.models.Config> configs) throws ExecutionException {
        Map<String, String> kafkaTopicConfigs = new HashMap<>();
        configs.forEach(c-> kafkaTopicConfigs.put(c.getName(), c.getValue()));
        NewTopic topic = new NewTopic(name, partitions, replicationFactor).configs(kafkaTopicConfigs);
        Logger.call(kafkaModule
            .getAdminClient(clusterId)
            .createTopics(Collections.singleton(topic))
            .all(),
            "Create Topics",
            Collections.singletonList(name)
        );
        auditModule.save(TopicAuditEvent.newTopic(clusterId, name, partitions, kafkaTopicConfigs));
        listTopics = new HashMap<>();
    }
    public void alterTopicPartition(String clusterId, String name, int partitions) throws ExecutionException {
        Map<String, NewPartitions> newPartitionMap = new HashMap<>();
        newPartitionMap.put(name, NewPartitions.increaseTo(partitions));
        Logger.call(kafkaModule
            .getAdminClient(clusterId)
            .createPartitions(newPartitionMap).all(),
            "Increase Topic partition",
            Collections.singletonList(name)
        );
        auditModule.save(TopicAuditEvent.increasePartitions(clusterId, name, partitions));
    }
    public void deleteTopics(String clusterId, String name) throws ExecutionException {
        Logger.call(kafkaModule.getAdminClient(clusterId)
            .deleteTopics(Collections.singleton(name))
            .all(),
            "Delete Topic",
            Collections.singletonList(name)
        );
        auditModule.save(TopicAuditEvent.deleteTopic(clusterId, name));
        listTopics = new HashMap<>();
    }
    private final Map<String, Map<String, List<Partition.Offsets>>> describeTopicsOffsets = new HashMap<>();
    public Map<String, List<Partition.Offsets>> describeTopicsOffsets(String clusterId, List<String> topics) throws ExecutionException, InterruptedException {
        describeTopicsOffsets.computeIfAbsent(clusterId, s -> new HashMap<>());
        List<String> list = new ArrayList<>(topics);
        list.removeIf(value -> this.describeTopicsOffsets.get(clusterId).containsKey(value));
        if (list.size() > 0) {
            Map<String, List<Partition.Offsets>> finalOffsets = Logger.call(
                () -> {
                    List<TopicPartition> collect = this.describeTopics(clusterId, topics).entrySet()
                        .stream()
                        .flatMap(topicDescription -> topicDescription
                            .getValue()
                            .partitions()
                            .stream()
                            .map(topicPartitionInfo ->
                                new TopicPartition(topicDescription.getValue().name(), topicPartitionInfo.partition())
                            )
                        )
                        .collect(Collectors.toList());
                    Map<TopicPartition, Long> startOffsetsToSearch = collect.stream().map(p ->
                        new AbstractMap.SimpleEntry<>(p, 0L))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                    KafkaConsumer<byte[], byte[]> consumer = kafkaModule.getConsumer(clusterId);
                    // KAFKA-7556: beginningOffsets always return 0. Use offsetsForTimes instead
                    Map<TopicPartition, OffsetAndTimestamp> begins = consumer.offsetsForTimes(startOffsetsToSearch);
                    Map<TopicPartition, Long> ends = consumer.endOffsets(collect);
                    consumer.close();
                    return begins.entrySet().stream()
                        .collect(groupingBy(
                            o -> o.getKey().topic(),
                            mapping(
                                begin ->
                                    new Partition.Offsets(
                                        begin.getKey().partition(),
                                        begin.getValue() != null ? begin.getValue().offset() : ends.get(begin.getKey()),
                                        ends.get(begin.getKey())
                                    ),
                                toList()
                            )
                        ));
                },
                "Describe Topics Offsets {}",
                topics
            );
            this.describeTopicsOffsets.get(clusterId).putAll(finalOffsets);
        }
        return this.describeTopicsOffsets.get(clusterId);
    }
    private final Map<String, Collection<ConsumerGroupListing>> listConsumerGroups = new HashMap<>();
    public Collection<ConsumerGroupListing> listConsumerGroups(String clusterId) throws ExecutionException {
        if (!this.listConsumerGroups.containsKey(clusterId)) {
            this.listConsumerGroups.put(clusterId, Logger.call(
                kafkaModule.getAdminClient(clusterId).listConsumerGroups().all(),
                "List ConsumerGroups",
                null
            ));
        }
        return this.listConsumerGroups.get(clusterId);
    }
    private Map<String, Map<String, ConsumerGroupDescription>> describeConsumerGroups = new HashMap<>();
    public Map<String, ConsumerGroupDescription> describeConsumerGroups(String clusterId, List<String> groups) throws ExecutionException {
        describeConsumerGroups.computeIfAbsent(clusterId, s -> new HashMap<>());
        List<String> list = new ArrayList<>(groups);
        list.removeIf(value -> this.describeConsumerGroups.get(clusterId).containsKey(value));
        if (list.size() > 0) {
            Map<String, ConsumerGroupDescription> description = Logger.call(
                kafkaModule.getAdminClient(clusterId)
                    .describeConsumerGroups(groups)
                    .all(),
                "Describe ConsumerGroups {}",
                groups
            );
            this.describeConsumerGroups.get(clusterId).putAll(description);
        }
        return this.describeConsumerGroups
            .get(clusterId)
            .entrySet()
            .stream()
            .filter(e -> groups.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    public void deleteConsumerGroups(String clusterId, String name) throws ApiException, ExecutionException {
        Logger.call(kafkaModule
            .getAdminClient(clusterId)
            .deleteConsumerGroups(Collections.singleton(name))
            .all(),
            "deleteConsumerGroups",
            Collections.singletonList(name)
        );
        auditModule.save(ConsumerGroupAuditEvent.deleteGroup(clusterId, name));
        describeConsumerGroups = new HashMap<>();
        consumerGroupOffset = new HashMap<>();
    }
    private Map<String, Map<String, Map<TopicPartition, OffsetAndMetadata>>> consumerGroupOffset = new HashMap<>();
    public Map<TopicPartition, OffsetAndMetadata> consumerGroupsOffsets(String clusterId, String groupId) throws ExecutionException {
        consumerGroupOffset.computeIfAbsent(clusterId, s -> new HashMap<>());
        if (!this.consumerGroupOffset.get(clusterId).containsKey(groupId)) {
            this.consumerGroupOffset.get(clusterId).put(groupId, Logger.call(
                kafkaModule.getAdminClient(clusterId)
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata(),
                "ConsumerGroup Offsets {}",
                Collections.singletonList(groupId)
            ));
        }
        return this.consumerGroupOffset.get(clusterId).get(groupId);
    }
    public void clearConsumerGroupsOffsets() {
        this.consumerGroupOffset = new HashMap<>();
    }
    private final Map<String, Map<Integer, Map<String, LogDirDescription>>> logDirs = new HashMap<>();
    public Map<Integer, Map<String, LogDirDescription>> describeLogDir(String clusterId) throws ExecutionException, InterruptedException {
        if (!this.logDirs.containsKey(clusterId)) {
            this.logDirs.put(clusterId, Logger.call(
                () -> {
                    try {
                        return kafkaModule.getAdminClient(clusterId)
                            .describeLogDirs(this.describeCluster(clusterId).nodes().get()
                                .stream()
                                .map(Node::id)
                                .collect(Collectors.toList())
                            )
                            .allDescriptions()
                            .get();
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof ClusterAuthorizationException || e.getCause() instanceof TopicAuthorizationException || e.getCause() instanceof UnsupportedVersionException ||
                            e.getCause() instanceof TimeoutException) {
                            return new HashMap<>();
                        }
                        if (e.getCause() instanceof ApiException) {
                            throw (ApiException) e.getCause();
                        }
                        throw e;
                    }
                },
                "List Log dir",
                null
            ));
        }
        return this.logDirs.get(clusterId);
    }
    private Map<String, Map<ConfigResource, Config>> describeConfigs = new HashMap<>();
    public Map<ConfigResource, Config> describeConfigs(String clusterId, ConfigResource.Type type, List<String> names) throws ExecutionException, InterruptedException {
        describeConfigs.computeIfAbsent(clusterId, s -> new HashMap<>());
        List<String> list = new ArrayList<>(names);
        list.removeIf(value -> this.describeConfigs.get(clusterId).entrySet()
            .stream()
            .filter(entry -> entry.getKey().type() == type)
            .anyMatch(entry -> entry.getKey().name().equals(value))
        );
        if (list.size() > 0) {
            Map<ConfigResource, Config> description = Logger.call(
                () -> {
                    try {
                        return kafkaModule.getAdminClient(clusterId)
                            .describeConfigs(names.stream()
                                .map(s -> new ConfigResource(type, s))
                                .collect(Collectors.toList())
                            )
                            .all()
                            .get();
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof SecurityDisabledException || e.getCause() instanceof ClusterAuthorizationException || e.getCause() instanceof TopicAuthorizationException) {
                            return ImmutableMap.of();
                        }
                        if (e.getCause() instanceof ApiException) {
                            throw (ApiException) e.getCause();
                        }
                        throw e;
                    }
                },
                "Describe Topic Config {}",
                names
            );
            this.describeConfigs.get(clusterId).putAll(description);
        }
        return this.describeConfigs
            .get(clusterId)
            .entrySet()
            .stream()
            .filter(e -> e.getKey().type() == type)
            .filter(e -> names.contains(e.getKey().name()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    public void alterConfigs(String clusterId, Map<ConfigResource, Config> configs) throws ExecutionException {
        Logger.call(
            kafkaModule.getAdminClient(clusterId)
                .alterConfigs(configs)
                .all(),
            "Alter configs",
            Collections.singletonList(clusterId)
        );
        configs.forEach(
            (k, v) -> {
                if (Objects.requireNonNull(k.type()) == ConfigResource.Type.TOPIC) {
                    auditModule.save(TopicAuditEvent.configChange(clusterId, k.name(),
                        v.entries().stream().collect(toMap(ConfigEntry::name, ConfigEntry::value))));
                }
            }
        );
        this.describeConfigs = new HashMap<>();
    }
    private final Map<String, Map<AclBindingFilter, Collection<AclBinding>>> describeAcls = new HashMap<>();
    public Collection<AclBinding> describeAcls(String clusterId, AclBindingFilter filter) throws ExecutionException, InterruptedException {
        describeAcls.computeIfAbsent(clusterId, s -> new HashMap<>());
        if (!this.describeAcls.get(clusterId).containsKey(filter)) {
            this.describeAcls.get(clusterId).put(filter, Logger.call(
                () -> {
                    try {
                        return kafkaModule.getAdminClient(clusterId)
                            .describeAcls(filter)
                            .values()
                            .get();
                    } catch (ApiException e) {
                        if (e.getCause() instanceof SecurityDisabledException || e.getCause() instanceof ClusterAuthorizationException || e.getCause() instanceof TopicAuthorizationException) {
                            return Collections.emptyList();
                        }
                        if (e.getCause() instanceof ApiException) {
                            throw (ApiException) e.getCause();
                        }
                        throw e;
                    }
                },
                "Describe Acls config",
                null
            ));
        }
        return describeAcls.get(clusterId).get(filter);
    }
    public void deleteConsumerGroupOffsets(String clusterId, String groupName, String topicName)
        throws ExecutionException {
        final Map<String, TopicDescription> topics = describeTopics(clusterId, List.of(topicName));
        if (topics.containsKey(topicName)) {
            final TopicDescription topic = topics.get(topicName);
            final Set<TopicPartition> topicPartitions = topic.partitions().stream()
                .map(p -> new TopicPartition(topicName, p.partition()))
                .collect(toSet());
            Logger.call(kafkaModule
                    .getAdminClient(clusterId)
                    .deleteConsumerGroupOffsets(groupName, topicPartitions)
                    .all(),
                "Delete consumer group offsets from topic {}",
                List.of(groupName, topicName)
            );
            auditModule.save(ConsumerGroupAuditEvent.deleteGroupOffsets(clusterId, groupName, topicName));
        }
    }
}
package org.akhq.models;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.kafka.common.TopicPartitionInfo;
import java.util.*;
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public class Partition {
    private Node.Partition leader;
    private int id;
    private String topic;
    private List<Node.Partition> nodes;
    private List<LogDir> logDir;
    private long firstOffset;
    private long lastOffset;
    public Partition(String topic, TopicPartitionInfo partitionInfo, List<LogDir> logDir, Offsets offsets) {
        this.id = partitionInfo.partition();
        this.topic = topic;
        this.logDir = logDir;
        this.firstOffset = offsets.getFirstOffset();
        this.lastOffset = offsets.getLastOffset();
        this.nodes = new ArrayList<>();
        for (org.apache.kafka.common.Node replica : partitionInfo.replicas()) {
            Node.Partition partition = new Node.Partition(
                replica,
                partitionInfo.leader().id() == replica.id(),
                partitionInfo.isr().stream().anyMatch(node -> node.id() == replica.id())
            );
            this.nodes.add(partition);
            if (partition.isLeader()) {
                this.leader = partition;
            }
        }
        if (this.leader == null) {
            org.apache.kafka.common.Node leader = partitionInfo.leader();
            this.leader = new Node.Partition(
                leader,
                true,
                partitionInfo.isr().stream().anyMatch(node -> node.id() == leader.id())
            );
        }
    }
    public Node.Partition getLeader() {
        return this.leader;
    }
    public long getLogDirSize() {
        return this.getLogDir().stream()
            .filter(logDir -> this.leader != null && logDir.getBrokerId() == this.leader.getId())
            .map(LogDir::getSize)
            .reduce(0L, Long::sum);
    }
    @ToString
    @EqualsAndHashCode
    @Getter
    @NoArgsConstructor
    public static class Offsets {
        private int partition;
        private long firstOffset;
        private long lastOffset;
        public Offsets(int partition, long start, long lastOffset) {
            this.partition = partition;
            this.firstOffset = start;
            this.lastOffset = lastOffset;
        }
    }
}
package org.akhq.models.audit;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.util.Map;
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TopicAuditEvent extends AuditEvent {
    private ActionType actionType;
    private String clusterId;
    private String topicName;
    private Integer partitions;
    private Map<String, String> config;
    public static TopicAuditEvent newTopic(String clusterId, String topicName, int partitions, Map<String, String> config) {
        return new TopicAuditEvent(ActionType.TOPIC_CREATE, clusterId, topicName, partitions, config);
    }
    public static TopicAuditEvent deleteTopic(String clusterId, String topicName) {
        return new TopicAuditEvent(ActionType.TOPIC_DELETE, clusterId, topicName, 0, null);
    }
    public static TopicAuditEvent configChange(String clusterId, String topicName, Map<String, String> config) {
        return new TopicAuditEvent(ActionType.TOPIC_CONFIG_CHANGE, clusterId, topicName, null, config);
    }
    public static TopicAuditEvent increasePartitions(String clusterId, String topicName, int partitions) {
        return new TopicAuditEvent(ActionType.TOPIC_INCREASE_PARTITION, clusterId, topicName, partitions, null);
    }
    @Override
    public String getType() {
        return "TOPIC";
    }
}
package org.akhq.models.audit;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class ConsumerGroupAuditEvent extends AuditEvent {
    private ActionType actionType;
    private String clusterId;
    private String consumerGroupName;
    private String topic;
    public static ConsumerGroupAuditEvent updateOffsets(String clusterId, String topicName, String consumerGroupName) {
        return new ConsumerGroupAuditEvent(ActionType.CONSUMER_GROUP_UPDATE_OFFSETS, clusterId, consumerGroupName, topicName);
    }
    public static ConsumerGroupAuditEvent deleteGroup(String clusterId, String consumerGroupName) {
        return new ConsumerGroupAuditEvent(ActionType.CONSUMER_GROUP_DELETE, clusterId, consumerGroupName, null);
    }
    public static ConsumerGroupAuditEvent deleteGroupOffsets(String clusterId, String consumerGroupName, String topicName) {
        return new ConsumerGroupAuditEvent(ActionType.CONSUMER_GROUP_DELETE_OFFSETS, clusterId, consumerGroupName, topicName);
    }
    @Override
    public String getType() {
        return "CONSUMER_GROUP";
    }
}
package org.akhq.modules;
import com.google.common.collect.ImmutableMap;
import io.confluent.kafka.schemaregistry.SchemaProvider;
import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.RestService;
import io.confluent.kafka.schemaregistry.client.security.SslFactory;
import io.confluent.kafka.schemaregistry.client.security.basicauth.BasicAuthCredentialProvider;
import io.confluent.kafka.schemaregistry.client.security.basicauth.BasicAuthCredentialProviderFactory;
import io.confluent.kafka.schemaregistry.client.security.basicauth.UserInfoCredentialProvider;
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchemaProvider;
import io.confluent.ksql.api.client.Client;
import io.confluent.ksql.api.client.ClientOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.akhq.configs.AbstractProperties;
import org.akhq.configs.Connection;
import org.akhq.configs.Default;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.codehaus.httpcache4j.uri.URIBuilder;
import org.sourcelab.kafka.connect.apiclient.Configuration;
import org.sourcelab.kafka.connect.apiclient.KafkaConnectClient;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
@Singleton
public class KafkaModule {
    @Inject
    private List<Connection> connections;
    @Inject
    private List<Default> defaults;
    private static final String INVALID_CLUSTER= "Invalid cluster '";
    private static final String SCHEMA_VERSION_FETCHER = "schemaVersionFetcher";
    public List<String> getClustersList() {
        return this.connections
            .stream()
            .map(r -> r.getName().split("\\.")[0])
            .distinct()
            .collect(Collectors.toList());
    }
    public boolean clusterExists(String cluster){
        return this.getClustersList().contains(cluster);
    }
    public Connection getConnection(String cluster) throws InvalidClusterException {
        if (!this.clusterExists(cluster)) {
            throw new InvalidClusterException(INVALID_CLUSTER + cluster + "'");
        }
        return this.connections
            .stream()
            .filter(r -> r.getName().equals(cluster))
            .findFirst()
            .get();
    }
    private Properties getDefaultsProperties(List<? extends AbstractProperties> current, String type) {
        Properties properties = new Properties();
        current
            .stream()
            .filter(r -> r.getName().equals(type))
            .forEach(r -> properties.putAll(r.getProperties()));
        return properties;
    }
    private Properties getConsumerProperties(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        Properties props = new Properties();
        props.putAll(this.getDefaultsProperties(this.defaults, "consumer"));
        props.putAll(this.getDefaultsProperties(this.connections, clusterId));
        return props;
    }
    private Properties getProducerProperties(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        Properties props = new Properties();
        props.putAll(this.getDefaultsProperties(this.defaults, "producer"));
        props.putAll(this.getDefaultsProperties(this.connections, clusterId));
        return props;
    }
    private Properties getAdminProperties(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        Properties props = new Properties();
        props.putAll(this.getDefaultsProperties(this.defaults, "admin"));
        props.putAll(this.getDefaultsProperties(this.connections, clusterId));
        return props;
    }
    private final Map<String, AdminClient> adminClient = new HashMap<>();
    public AdminClient getAdminClient(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        if (!this.adminClient.containsKey(clusterId)) {
            this.adminClient.put(clusterId, AdminClient.create(this.getAdminProperties(clusterId)));
        }
        return this.adminClient.get(clusterId);
    }
    public KafkaConsumer<byte[], byte[]> getConsumer(String clusterId) throws InvalidClusterException {
        return getConsumer(clusterId, new Properties());
    }
    public KafkaConsumer<byte[], byte[]> getConsumer(String clusterId, Properties properties) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        Properties props = this.getConsumerProperties(clusterId);
        props.putAll(properties);
        if (props.containsKey(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG) &&
                props.containsKey(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG) ) {
            return new KafkaConsumer<>(props);
        } else {
            return new KafkaConsumer<>(
                    props,
                    new ByteArrayDeserializer(),
                    new ByteArrayDeserializer()
                    );
        }
    }
    private final Map<String, KafkaProducer<byte[], byte[]>> producers = new HashMap<>();
    public KafkaProducer<byte[], byte[]> getProducer(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        if (!this.producers.containsKey(clusterId)) {
            Properties props = this.getProducerProperties(clusterId);
            if (props.containsKey(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG) &&
                    props.containsKey(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG) ) {
                this.producers.put(clusterId, new KafkaProducer<>(props));
            } else {
                this.producers.put(clusterId, new KafkaProducer<>(
                    props,
                    new ByteArraySerializer(),
                    new ByteArraySerializer()
                ));
            }
        }
        return this.producers.get(clusterId);
    }
    public AvroSchemaProvider getAvroSchemaProvider(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        AvroSchemaProvider avroSchemaProvider = new AvroSchemaProvider();
        avroSchemaProvider.configure(Collections.singletonMap(
            SCHEMA_VERSION_FETCHER,
            new CachedSchemaRegistryClient(this.getRegistryRestClient(clusterId), 1000)
        ));
        return avroSchemaProvider;
    }
    public JsonSchemaProvider getJsonSchemaProvider(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        JsonSchemaProvider jsonSchemaProvider = new JsonSchemaProvider();
        jsonSchemaProvider.configure(Collections.singletonMap(
            SCHEMA_VERSION_FETCHER,
            new CachedSchemaRegistryClient(this.getRegistryRestClient(clusterId), 1000)
        ));
        return jsonSchemaProvider;
    }
    public ProtobufSchemaProvider getProtobufSchemaProvider(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        ProtobufSchemaProvider protobufSchemaProvider = new ProtobufSchemaProvider();
        protobufSchemaProvider.configure(Collections.singletonMap(
            SCHEMA_VERSION_FETCHER,
            new CachedSchemaRegistryClient(this.getRegistryRestClient(clusterId), 1000)
        ));
        return protobufSchemaProvider;
    }
    public RestService getRegistryRestClient(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        Connection connection = this.getConnection(clusterId);
        if (connection.getSchemaRegistry() != null) {
            RestService restService = new RestService(
                connection.getSchemaRegistry().getUrl()
            );
            if (connection.getSchemaRegistry().getProperties() != null
                && !connection.getSchemaRegistry().getProperties().isEmpty()) {
                Map<String, Object> sslConfigs =
                    connection
                        .getSchemaRegistry()
                        .getProperties()
                        .entrySet()
                        .stream()
                        .filter(e -> e.getKey().startsWith("schema.registry."))
                        .collect(Collectors.toMap(e -> e.getKey().substring("schema.registry.".length()), Map.Entry::getValue));
                SslFactory sslFactory = new SslFactory(sslConfigs);
                if (sslFactory != null && sslFactory.sslContext() != null) {
                    restService.setSslSocketFactory(sslFactory.sslContext().getSocketFactory());
                }
            }
            restService.setHttpHeaders(Collections.singletonMap("Accept", "application/json"));
            if (connection.getSchemaRegistry().getBasicAuthUsername() != null) {
                BasicAuthCredentialProvider basicAuthCredentialProvider = BasicAuthCredentialProviderFactory
                    .getBasicAuthCredentialProvider(
                        new UserInfoCredentialProvider().alias(),
                        ImmutableMap.of(
                            "schema.registry.basic.auth.user.info",
                            connection.getSchemaRegistry().getBasicAuthUsername() + ":" +
                                connection.getSchemaRegistry().getBasicAuthPassword()
                        )
                    );
                restService.setBasicAuthCredentialProvider(basicAuthCredentialProvider);
            }
            if (connection.getSchemaRegistry().getProperties() != null) {
                restService.configure(connection.getSchemaRegistry().getProperties());
            }
            return restService;
        }
        return null;
    }
    private final Map<String, SchemaRegistryClient> registryClient = new HashMap<>();
    public SchemaRegistryClient getRegistryClient(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        if (!this.registryClient.containsKey(clusterId)) {
            Connection connection = this.getConnection(clusterId);
            List<SchemaProvider> providers = new ArrayList<>();
            providers.add(new AvroSchemaProvider());
            providers.add(new JsonSchemaProvider());
            providers.add(new ProtobufSchemaProvider());
            SchemaRegistryClient client = new CachedSchemaRegistryClient(
                this.getRegistryRestClient(clusterId),
                1000,
                providers,
                connection.getSchemaRegistry() != null ? connection.getSchemaRegistry().getProperties() : null,
                null
            );
            this.registryClient.put(clusterId, client);
        }
        return this.registryClient.get(clusterId);
    }
    private final Map<String, Map<String, KafkaConnectClient>> connectRestClient = new HashMap<>();
    public Map<String, KafkaConnectClient> getConnectRestClient(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        if (!this.connectRestClient.containsKey(clusterId)) {
            Connection connection = this.getConnection(clusterId);
            if (connection.getConnect() != null && !connection.getConnect().isEmpty()) {
                Map<String, KafkaConnectClient> mapConnects = new HashMap<>();
                connection.getConnect().forEach(connect -> {
                    URIBuilder uri = URIBuilder.fromString(connect.getUrl().toString());
                    Configuration configuration = new Configuration(uri.toNormalizedURI(false).toString());
                    if (connect.getBasicAuthUsername() != null) {
                        configuration.useBasicAuth(
                            connect.getBasicAuthUsername(),
                            connect.getBasicAuthPassword()
                        );
                    }
                    if (connect.getSslTrustStore() != null) {
                        configuration.useTrustStore(
                            new File(connect.getSslTrustStore()),
                            connect.getSslTrustStorePassword()
                        );
                    }
                    if (connect.getSslKeyStore() != null) {
                        configuration.useKeyStore(
                            new File(connect.getSslKeyStore()),
                            connect.getSslKeyStorePassword()
                        );
                    }
                    mapConnects.put(connect.getName(), new KafkaConnectClient(configuration));
                });
                this.connectRestClient.put(clusterId, mapConnects);
            }
        }
        return this.connectRestClient.get(clusterId);
    }
    private final Map<String, Map<String, Client>> ksqlDbClient = new HashMap<>();
    public Map<String, Client> getKsqlDbClient(String clusterId) throws InvalidClusterException {
        if (!this.clusterExists(clusterId)) {
            throw new InvalidClusterException(INVALID_CLUSTER + clusterId + "'");
        }
        if (!this.ksqlDbClient.containsKey(clusterId)) {
            Connection connection = this.getConnection(clusterId);
            if (connection.getKsqldb() != null && !connection.getKsqldb().isEmpty()) {
                Map<String, Client> mapKsqlDbs = new HashMap<>();
                connection.getKsqldb().forEach(ksqlDb -> {
                    URIBuilder uri = URIBuilder.fromString(ksqlDb.getUrl().toString());
                    ClientOptions options = ClientOptions.create()
                        .setHost(uri.getHost().get())
                        .setPort(uri.getPort().get())
                        .setUseTls(ksqlDb.isUseTls())
                        .setUseAlpn(ksqlDb.isUseAlpn())
                        .setVerifyHost(ksqlDb.isVerifyHost());
                    if (ksqlDb.getBasicAuthUsername() != null && ksqlDb.getBasicAuthPassword() != null) {
                        options.setBasicAuthCredentials(ksqlDb.getBasicAuthUsername(), ksqlDb.getBasicAuthPassword());
                    }
                    Client client = Client.create(options);
                    mapKsqlDbs.put(ksqlDb.getName(), client);
                });
                this.ksqlDbClient.put(clusterId, mapKsqlDbs);
            }
        }
        return this.ksqlDbClient.get(clusterId);
    }
}
package org.akhq.modules;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.security.utils.SecurityService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.akhq.configs.Audit;
import org.akhq.models.audit.AuditEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import java.util.Optional;
@Singleton
@Slf4j
public class AuditModule {
    @Inject
    Optional<SecurityService> securityService;
    @Inject
    KafkaModule kafkaModule;
    @Inject
    Audit auditConfig;
    private final ObjectMapper mapper = new ObjectMapper();
    public void save(AuditEvent event) {
        final String clusterId = auditConfig.getClusterId();
        final String topicName = auditConfig.getTopicName();
        if (!auditConfig.getEnabled()) {
            return;
        }
        byte[] value;
        securityService.flatMap(SecurityService::username).ifPresent(event::setUserName);
        try {
            value = mapper.writeValueAsBytes(event);
        } catch (Exception e) {
            log.error("Audit event cannot be serialized to JSON", e);
            return;
        }
        kafkaModule.getProducer(clusterId).send(new ProducerRecord<>(topicName, value), (metadata, exception) -> {
            if (exception != null) {
                log.error("Audit data cannot be sent to Kafka", exception);
            }
        });
    }
}
package org.akhq.modules;
import io.micronaut.runtime.http.scope.RequestScope;
@RequestScope
public class KafkaWrapperRequestScope extends AbstractKafkaWrapper {
}
package org.akhq.controllers;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.akhq.configs.security.Role;
import org.akhq.models.AccessControl;
import org.akhq.models.Consumer;
import org.akhq.models.ConsumerGroup;
import org.akhq.models.TopicPartition;
import org.akhq.modules.AbstractKafkaWrapper;
import org.akhq.repositories.AccessControlListRepository;
import org.akhq.repositories.ConsumerGroupRepository;
import org.akhq.repositories.RecordRepository;
import org.akhq.security.annotation.AKHQSecured;
import org.akhq.utils.Pagination;
import org.akhq.utils.ResultPagedList;
import org.apache.kafka.common.resource.ResourceType;
import org.codehaus.httpcache4j.uri.URIBuilder;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import jakarta.inject.Inject;
@AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.READ)
@Controller("/api/{cluster}/group")
public class GroupController extends AbstractController {
    private final AbstractKafkaWrapper kafkaWrapper;
    private final ConsumerGroupRepository consumerGroupRepository;
    private final RecordRepository recordRepository;
    private final AccessControlListRepository aclRepository;
    @Value("${akhq.pagination.page-size}")
    private Integer pageSize;
    @Inject
    public GroupController(
        AbstractKafkaWrapper kafkaWrapper,
        ConsumerGroupRepository consumerGroupRepository,
        RecordRepository recordRepository,
        AccessControlListRepository aclRepository
    ) {
        this.kafkaWrapper = kafkaWrapper;
        this.consumerGroupRepository = consumerGroupRepository;
        this.recordRepository = recordRepository;
        this.aclRepository = aclRepository;
    }
    @Get
    @Operation(tags = {"consumer group"}, summary = "List all consumer groups")
    public ResultPagedList<ConsumerGroup> list(HttpRequest<?> request, String cluster, Optional<String> search, Optional<Integer> page) throws ExecutionException, InterruptedException {
        checkIfClusterAllowed(cluster);
        URIBuilder uri = URIBuilder.fromURI(request.getUri());
        Pagination pagination = new Pagination(pageSize, uri, page.orElse(1));
        return ResultPagedList.of(this.consumerGroupRepository.list(cluster, pagination, search, buildUserBasedResourceFilters(cluster)));
    }
    @Get("{groupName}")
    @Operation(tags = {"consumer group"}, summary = "Retrieve a consumer group")
    public ConsumerGroup home(String cluster, String groupName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);
        return this.consumerGroupRepository.findByName(cluster, groupName, buildUserBasedResourceFilters(cluster));
    }
    @Get("{groupName}/offsets")
    @Operation(tags = {"consumer group"}, summary = "Retrieve a consumer group offsets")
    public List<TopicPartition.ConsumerGroupOffset> offsets(String cluster, String groupName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);
        return this.consumerGroupRepository.findByName(cluster, groupName, buildUserBasedResourceFilters(cluster)).getOffsets();
    }
    @Get("{groupName}/members")
    @Operation(tags = {"consumer group"}, summary = "Retrieve a consumer group members")
    public List<Consumer> members(String cluster, String groupName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);
        return this.consumerGroupRepository.findByName(cluster, groupName, buildUserBasedResourceFilters(cluster)).getMembers();
    }
    @Get("{groupName}/acls")
    @Operation(tags = {"consumer group"}, summary = "Retrieve a consumer group acls")
    public List<AccessControl> acls(String cluster, String groupName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);
        return aclRepository.findByResourceType(cluster, ResourceType.GROUP, groupName);
    }
    @Get("topics")
    @Operation(tags = {"consumer group"}, summary = "Retrieve consumer group for list of topics")
    public List filterByTopics(String cluster, Optional<List<String>> topics) {
        checkIfClusterAllowed(cluster);
        return topics.map(
                topicsName -> {
                    try {
                        return this.consumerGroupRepository.findByTopics(cluster, topicsName,
                            buildUserBasedResourceFilters(cluster));
                    } catch (ExecutionException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
        ).orElse(Collections.EMPTY_LIST);
    }
    @AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.UPDATE_OFFSET)
    @Post(value = "{groupName}/offsets", consumes = MediaType.APPLICATION_JSON)
    @Operation(tags = {"consumer group"}, summary = "Update consumer group offsets")
    public HttpResponse<?> offsets(
        String cluster,
        String groupName,
        @Body List<OffsetsUpdate> offsets
    ) {
        checkIfClusterAndResourceAllowed(cluster, groupName);
        this.consumerGroupRepository.updateOffsets(
            cluster,
            groupName,
            offsets
                .stream()
                .map(r -> new AbstractMap.SimpleEntry<>(
                        new TopicPartition(r.getTopic(), r.getPartition()),
                        r.getOffset()
                    )
                )
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );
        return HttpResponse.noContent();
    }
    @AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.UPDATE_OFFSET)
    @Get("{groupName}/offsets/start")
    @Operation(tags = {"consumer group"}, summary = "Retrive consumer group offsets by timestamp")
    public List<RecordRepository.TimeOffset> offsetsStart(String cluster, String groupName, Instant timestamp) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);
        ConsumerGroup group = this.consumerGroupRepository.findByName(
            cluster, groupName, buildUserBasedResourceFilters(cluster));
        return recordRepository.getOffsetForTime(
            cluster,
            group.getOffsets()
                .stream()
                .map(r -> new TopicPartition(r.getTopic(), r.getPartition()))
                .collect(Collectors.toList()),
            timestamp.toEpochMilli()
        );
    }
    @AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.DELETE)
    @Delete("{groupName}")
    @Operation(tags = {"consumer group"}, summary = "Delete a consumer group")
    public HttpResponse<?> delete(String cluster, String groupName) throws ExecutionException, InterruptedException {
        checkIfClusterAndResourceAllowed(cluster, groupName);
        this.kafkaWrapper.deleteConsumerGroups(cluster, groupName);
        return HttpResponse.noContent();
    }
    @AKHQSecured(resource = Role.Resource.CONSUMER_GROUP, action = Role.Action.DELETE_OFFSET)
    @Delete("{groupName}/topic/{topicName}")
    @Operation(tags = {"consumer group"}, summary = "Delete group offsets of given topic")
    public HttpResponse<?> deleteConsumerGroupOffsets(String cluster, String groupName, String topicName) throws ExecutionException {
        checkIfClusterAndResourceAllowed(cluster, groupName);
        this.kafkaWrapper.deleteConsumerGroupOffsets(cluster, groupName, topicName);
        return HttpResponse.noContent();
    }
    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    public static class OffsetsUpdate {
        private String topic;
        private int partition;
        private long offset;
    }
}
package org.akhq.repositories;
import org.akhq.models.AccessControl;
import org.akhq.modules.AbstractKafkaWrapper;
import org.apache.kafka.common.acl.*;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePatternFilter;
import org.apache.kafka.common.resource.ResourceType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
@Singleton
public class AccessControlListRepository extends AbstractRepository {
    @Inject
    private AbstractKafkaWrapper kafkaWrapper;
    public List<AccessControl> findAll(String clusterId, Optional<String> search, List<String> filters) throws ExecutionException, InterruptedException {
        return toGroupedAcl(kafkaWrapper
            .describeAcls(clusterId, AclBindingFilter.ANY)
            .stream()
            .filter(aclBinding -> isSearchMatch(search, aclBinding.entry().principal())
                && isMatchRegex(filters, aclBinding.entry().principal()))
            .collect(Collectors.toList())
        );
    }
    public AccessControl findByPrincipal(String clusterId, String encodedPrincipal, Optional<ResourceType> resourceType) throws ExecutionException, InterruptedException {
        String principal = AccessControl.decodePrincipal(encodedPrincipal);
        return new AccessControl(
            principal,
            kafkaWrapper.describeAcls(clusterId, filterForPrincipal(principal, resourceType))
        );
    }
    public List<AccessControl> findByResourceType(String clusterId, ResourceType resourceType, String resourceName) throws ExecutionException, InterruptedException {
        return toGroupedAcl(kafkaWrapper.describeAcls(clusterId, filterForResource(resourceType, resourceName)));
    }
    private static AclBindingFilter filterForResource(ResourceType resourceType, String resourceName) {
        ResourcePatternFilter resourcePatternFilter = new ResourcePatternFilter(resourceType, resourceName, PatternType.ANY);
        return new AclBindingFilter(resourcePatternFilter, AccessControlEntryFilter.ANY);
    }
    private static AclBindingFilter filterForPrincipal(String principal, Optional<ResourceType> resourceTypeFilter) {
        AccessControlEntryFilter accessControlEntryFilter = new AccessControlEntryFilter(principal, null, AclOperation.ANY, AclPermissionType.ANY);
        ResourcePatternFilter resourcePatternFilter = new ResourcePatternFilter(resourceTypeFilter.orElse(ResourceType.ANY), null, PatternType.ANY);
        return new AclBindingFilter(resourcePatternFilter, accessControlEntryFilter);
    }
    private static List<AccessControl> toGroupedAcl(Collection<AclBinding> aclBindings) {
        return aclBindings
            .stream()
            .collect(
                Collectors.groupingBy(
                    acl -> acl.entry().principal(),
                    Collectors.toList()
                )
            )
            .entrySet()
            .stream()
            .map(entry -> new AccessControl(
                entry.getKey(),
                entry.getValue()
            ))
            .collect(Collectors.toList());
    }
}
package org.akhq.repositories;
import io.micronaut.context.ApplicationContext;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.utils.SecurityService;
import org.akhq.models.ConsumerGroup;
import org.akhq.models.Partition;
import org.akhq.models.audit.ConsumerGroupAuditEvent;
import org.akhq.modules.AbstractKafkaWrapper;
import org.akhq.modules.AuditModule;
import org.akhq.modules.KafkaModule;
import org.akhq.utils.PagedList;
import org.akhq.utils.Pagination;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
@Singleton
public class ConsumerGroupRepository extends AbstractRepository {
    @Inject
    AbstractKafkaWrapper kafkaWrapper;
    @Inject
    private KafkaModule kafkaModule;
    @Inject
    private ApplicationContext applicationContext;
    @Inject
    private AuditModule auditModule;
    public PagedList<ConsumerGroup> list(String clusterId, Pagination pagination, Optional<String> search, List<String> filters) throws ExecutionException, InterruptedException {
        return PagedList.of(all(clusterId, search, filters), pagination, groupsList -> this.findByName(clusterId, groupsList, filters));
    }
    public List<String> all(String clusterId, Optional<String> search, List<String> filters) throws ExecutionException, InterruptedException {
        ArrayList<String> list = new ArrayList<>();
        for (ConsumerGroupListing item : kafkaWrapper.listConsumerGroups(clusterId)) {
            if (isSearchMatch(search, item.groupId()) && isMatchRegex(filters, item.groupId())) {
                list.add(item.groupId());
            }
        }
        list.sort(Comparator.comparing(String::toLowerCase));
        return list;
    }
    public ConsumerGroup findByName(String clusterId, String name, List<String> filters) throws ExecutionException, InterruptedException {
        Optional<ConsumerGroup> consumerGroup = Optional.empty();
        if (isMatchRegex(filters, name)) {
            consumerGroup = this.findByName(clusterId, Collections.singletonList(name), filters).stream().findFirst();
        }
        return consumerGroup.orElseThrow(() -> new NoSuchElementException("Consumer Group '" + name + "' doesn't exist"));
    }
    public List<ConsumerGroup> findByName(String clusterId, List<String> groups, List<String> filters) throws ExecutionException, InterruptedException {
        List<String> filteredConsumerGroups = groups.stream()
            .filter(t -> isMatchRegex(filters, t))
            .collect(Collectors.toList());
        Map<String, ConsumerGroupDescription> consumerDescriptions = kafkaWrapper.describeConsumerGroups(clusterId, filteredConsumerGroups);
        Map<String, Map<TopicPartition, OffsetAndMetadata>> groupGroupsOffsets = consumerDescriptions.keySet().stream()
            .map(group -> {
                try {
                    return new AbstractMap.SimpleEntry<>(group, kafkaWrapper.consumerGroupsOffsets(clusterId, group));
                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                }
            })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<String> topics = groupGroupsOffsets.values().stream()
            .map(Map::keySet)
            .flatMap(Set::stream)
            .map(TopicPartition::topic)
            .distinct()
            .collect(Collectors.toList());
        Map<String, List<Partition.Offsets>> topicTopicsOffsets = kafkaWrapper.describeTopicsOffsets(clusterId, topics);
        return consumerDescriptions.values().stream()
            .map(consumerGroupDescription -> new ConsumerGroup(
                consumerGroupDescription,
                groupGroupsOffsets.get(consumerGroupDescription.groupId()),
                groupGroupsOffsets.get(consumerGroupDescription.groupId()).keySet().stream()
                    .map(TopicPartition::topic)
                    .distinct()
                    .collect(Collectors.toMap(Function.identity(), topicTopicsOffsets::get))
            ))
            .sorted(Comparator.comparing(ConsumerGroup::getId))
            .collect(Collectors.toList());
    }
    public List<ConsumerGroup> findByTopic(String clusterId, String topic, List<String> filters) throws ExecutionException, InterruptedException {
        List<String> groupName = this.all(clusterId, Optional.empty(), filters);
        List<ConsumerGroup> list = this.findByName(clusterId, groupName, filters);
        return list
            .stream()
            .filter(consumerGroups ->
                consumerGroups.getActiveTopics()
                    .stream()
                    .anyMatch(s -> Objects.equals(s, topic)) ||
                    consumerGroups.getTopics()
                        .stream()
                        .anyMatch(s -> Objects.equals(s, topic))
            )
            .collect(Collectors.toList());
    }
    public List<ConsumerGroup> findByTopics(String clusterId, List<String> topics, List<String> filters) throws ExecutionException, InterruptedException {
        List<String> groupName = this.all(clusterId, Optional.empty(), filters);
        List<ConsumerGroup> list = this.findByName(clusterId, groupName, filters);
        return list
            .stream()
            .filter(consumerGroups ->
                consumerGroups.getActiveTopics()
                    .stream()
                    .anyMatch(s -> topics.contains(s)) ||
                    consumerGroups.getTopics()
                        .stream()
                        .anyMatch(s -> topics.contains(s))
            )
            .collect(Collectors.toList());
    }
    public void updateOffsets(String clusterId, String name, Map<org.akhq.models.TopicPartition, Long> offset) {
        KafkaConsumer<byte[], byte[]> consumer = kafkaModule.getConsumer(clusterId, new Properties() {{
            put(ConsumerConfig.GROUP_ID_CONFIG, name);
        }});
        Map<TopicPartition, OffsetAndMetadata> offsets = offset
            .entrySet()
            .stream()
            .map(r -> new AbstractMap.SimpleEntry<>(
                new TopicPartition(r.getKey().getTopic(), r.getKey().getPartition()),
                new OffsetAndMetadata(r.getValue())
            ))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        consumer.commitSync(offsets);
        consumer.close();
        offset.forEach(
            (k, v) -> auditModule.save(ConsumerGroupAuditEvent.updateOffsets(clusterId, k.getTopic(), name))
        );
        kafkaWrapper.clearConsumerGroupsOffsets();
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
package org.akhq.repositories;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.retry.annotation.Retryable;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.utils.SecurityService;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.TopicListing;
import org.akhq.models.Partition;
import org.akhq.models.Topic;
import org.akhq.modules.AbstractKafkaWrapper;
import org.akhq.utils.PagedList;
import org.akhq.utils.Pagination;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
@Singleton
public class TopicRepository extends AbstractRepository {
    @Inject
    private AbstractKafkaWrapper kafkaWrapper;
    @Inject
    private LogDirRepository logDirRepository;
    @Inject
    private ConfigRepository configRepository;
    @Inject
    private ApplicationContext applicationContext;
    @Value("${akhq.topic.internal-regexps}")
    protected List<String> internalRegexps;
    @Value("${akhq.topic.stream-regexps}")
    protected List<String> streamRegexps;
    public enum TopicListView {
        ALL,
        HIDE_INTERNAL,
        HIDE_INTERNAL_STREAM,
        HIDE_STREAM,
    }
    public PagedList<Topic> list(String clusterId, Pagination pagination, TopicListView view, Optional<String> search, List<String> filters) throws ExecutionException, InterruptedException {
        List<String> all = all(clusterId, view, search, filters);
        return PagedList.of(all, pagination, topicList -> this.findByName(clusterId, topicList));
    }
    public List<String> all(String clusterId, TopicListView view, Optional<String> search, List<String> filters) throws ExecutionException, InterruptedException {
        return kafkaWrapper.listTopics(clusterId)
            .stream()
            .map(TopicListing::name)
            .filter(name -> isSearchMatch(search, name) && isMatchRegex(filters, name))
            .filter(name -> isListViewMatch(view, name))
            .sorted(Comparator.comparing(String::toLowerCase))
            .collect(Collectors.toList());
    }
    public boolean isListViewMatch(TopicListView view, String value) {
        switch (view) {
            case HIDE_STREAM:
                return !isStream(value);
            case HIDE_INTERNAL:
                return !isInternal(value);
            case HIDE_INTERNAL_STREAM:
                return !isInternal(value) && !isStream(value);
        }
        return true;
    }
    public Topic findByName(String clusterId, String name) throws ExecutionException, InterruptedException {
        return this.findByName(clusterId, Collections.singletonList(name))
            .stream()
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("Topic '" + name + "' doesn't exist"));
    }
    public List<Topic> findByName(String clusterId, List<String> topics) throws ExecutionException, InterruptedException {
        ArrayList<Topic> list = new ArrayList<>();
        Set<Map.Entry<String, TopicDescription>> topicDescriptions = kafkaWrapper.describeTopics(clusterId, topics).entrySet();
        Map<String, List<Partition.Offsets>> topicOffsets = kafkaWrapper.describeTopicsOffsets(clusterId, topics);
        for (Map.Entry<String, TopicDescription> description : topicDescriptions) {
                list.add(
                    new Topic(
                        description.getValue(),
                        logDirRepository.findByTopic(clusterId, description.getValue().name()),
                        topicOffsets.get(description.getValue().name()),
                        isInternal(description.getValue().name()),
                        isStream(description.getValue().name())
                    )
                );
        }
        list.sort(Comparator.comparing(Topic::getName));
        return list;
    }
    private boolean isInternal(String name) {
        return this.internalRegexps
            .stream()
            .anyMatch(name::matches);
    }
    private boolean isStream(String name) {
        return this.streamRegexps
            .stream()
            .anyMatch(name::matches);
    }
    public void create(String clusterId, String name, int partitions, short replicationFactor, List<org.akhq.models.Config> configs) throws ExecutionException, InterruptedException {
        kafkaWrapper.createTopics(clusterId, name, partitions, replicationFactor, configs);
    }
    public void delete(String clusterId, String name) throws ExecutionException, InterruptedException {
        kafkaWrapper.deleteTopics(clusterId, name);
    }
    public void increasePartition(String clusterId, String name, int partitions) throws ExecutionException, InterruptedException {
        kafkaWrapper.alterTopicPartition(clusterId, name, partitions);
    }
    @Retryable(
        includes = {
            UnknownTopicOrPartitionException.class
        }, delay = "${akhq.topic.retry.topic-exists.delay:3s}")
    void checkIfTopicExists(String clusterId, String name) throws ExecutionException {
        kafkaWrapper.describeTopics(clusterId, Collections.singletonList(name));
    }
}
package org.akhq.repositories;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.ReplicaInfo;
import org.akhq.models.LogDir;
import org.akhq.modules.AbstractKafkaWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
@Singleton
public class LogDirRepository extends AbstractRepository {
    @Inject
    AbstractKafkaWrapper kafkaWrapper;
    public List<LogDir> list(String clusterId) throws ExecutionException, InterruptedException {
        List<LogDir> list = new ArrayList<>();
        for(Map.Entry<Integer, Map<String, LogDirDescription>> nodes : kafkaWrapper.describeLogDir(clusterId).entrySet()) {
            for(Map.Entry<String, LogDirDescription> node: nodes.getValue().entrySet()) {
                for(Map.Entry<TopicPartition, ReplicaInfo> log: node.getValue().replicaInfos().entrySet()) {
                    list.add(new LogDir(nodes.getKey(), node.getKey(), log.getKey(), log.getValue()));
                }
            }
        }
        return list;
    }
    public List<LogDir> findByTopic(String clusterId, String topic) throws ExecutionException, InterruptedException {
        return this.list(clusterId).stream()
            .filter(item -> item.getTopic().equals(topic))
            .collect(Collectors.toList());
    }
    public List<LogDir> findByBroker(String clusterId, Integer brokerId) throws ExecutionException, InterruptedException {
        return this.list(clusterId).stream()
            .filter(item -> item.getBrokerId().equals(brokerId))
            .collect(Collectors.toList());
    }
}
package org.akhq.repositories;
import com.google.common.collect.ImmutableMap;
import org.akhq.models.Config;
import org.akhq.modules.AbstractKafkaWrapper;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
@Singleton
public class ConfigRepository extends AbstractRepository {
    @Inject
    AbstractKafkaWrapper kafkaWrapper;
    public List<Config> findByBroker(String clusterId, Integer name) throws ExecutionException, InterruptedException {
        return this.findByBrokers(clusterId, Collections.singletonList(name)).get(String.valueOf(name));
    }
    public Map<String, List<Config>> findByBrokers(String clusterId, List<Integer> names) throws ExecutionException, InterruptedException {
        return this.find(
            clusterId,
            ConfigResource.Type.BROKER,
            names
                .stream()
                .map(String::valueOf)
                .collect(Collectors.toList())
        );
    }
    public List<Config> findByTopic(String clusterId, String name) throws ExecutionException, InterruptedException {
        return this.findByTopics(clusterId, Collections.singletonList(name)).get(name);
    }
    public Map<String, List<Config>> findByTopics(String clusterId, List<String> names) throws ExecutionException, InterruptedException {
        return this.find(clusterId, ConfigResource.Type.TOPIC, names);
    }
    private Map<String, List<Config>> find(String clusterId, ConfigResource.Type type, List<String> names) throws ExecutionException, InterruptedException {
        Map<String, List<Config>> map = new HashMap<>();
        kafkaWrapper.describeConfigs(clusterId, type, names).forEach((key, value) -> {
            List<Config> collect = value.entries()
                .stream()
                .map(Config::new)
                .sorted(Comparator.comparing(Config::getName))
                .collect(Collectors.toList());
            map.put(key.name(), collect);
        });
        return map;
    }
    public void updateBroker(String clusterId, Integer name, List<Config> configs) throws ExecutionException, InterruptedException {
        this.update(clusterId, ConfigResource.Type.BROKER, String.valueOf(name), configs);
    }
    public void updateTopic(String clusterId, String name, List<Config> configs) throws ExecutionException, InterruptedException {
        this.update(clusterId, ConfigResource.Type.TOPIC, name, configs);
    }
    private void update(String clusterId, ConfigResource.Type type, String name, List<Config> configs) throws ExecutionException, InterruptedException {
        List<ConfigEntry> entries = new ArrayList<>();
        List<String> configNamesToReset = configs.stream()
            .filter(Config::shouldResetToDefault)
            .map(Config::getName)
            .collect(Collectors.toList());
        this.find(clusterId, type, Collections.singletonList(name))
            .get(name)
            .stream()
            .filter(config -> config.getSource().name().startsWith("DYNAMIC_"))
            .filter(config -> !configNamesToReset.contains(config.getName()))
            .forEach(config -> entries.add(new ConfigEntry(config.getName(), config.getValue())));
        configs.stream()
            .filter(config -> !config.shouldResetToDefault())
            .map(config -> new ConfigEntry(config.getName(), config.getValue()))
            .forEach(entries::add);
        kafkaWrapper.alterConfigs(clusterId, ImmutableMap.of(
            new ConfigResource(type, name),
            new org.apache.kafka.clients.admin.Config(entries)
        ));
    }
    public static List<Config> updatedConfigs(Map<String, String> request, List<Config> configs, boolean html) {
        Function<Config, String> configFn = html ?
            (Config config) -> "configs[" + config.getName() + "]" :
            Config::getName;
        return configs
            .stream()
            .filter(config -> !config.isReadOnly())
            .filter(config -> request.containsKey(configFn.apply(config)))
            .filter(config -> {
                String current = config.getValue() == null ? "" : config.getValue();
                return !(current).equals(request.get(configFn.apply(config)));
            })
            .map(config -> config.withValue(request.get(configFn.apply(config))))
            .collect(Collectors.toList());
    }
}