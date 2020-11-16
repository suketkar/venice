package com.linkedin.venice.storagenode;

import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.client.exceptions.VeniceClientException;
import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.client.store.deserialization.BatchDeserializerType;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.compression.CompressorFactory;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.SchemaResponse;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.controllerapi.VersionCreationResponse;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.helix.HelixReadOnlySchemaRepository;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.serialization.DefaultSerializer;
import com.linkedin.venice.serialization.VeniceKafkaSerializer;
import com.linkedin.venice.serialization.avro.VeniceAvroKafkaSerializer;
import com.linkedin.venice.serializer.AvroGenericDeserializer;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.writer.VeniceWriter;
import com.linkedin.venice.writer.VeniceWriterFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.linkedin.venice.VeniceConstants.*;


@Test(singleThreaded = true)
public class ReadComputeValidationTest {
  private static final String valuePrefix = "id_";
  private VeniceClusterWrapper veniceCluster;
  private int valueSchemaId;
  private String storeName;
  private String routerAddr;
  private VeniceKafkaSerializer keySerializer;
  private VeniceKafkaSerializer valueSerializer;
  private VeniceKafkaSerializer valueSerializer2;

  private static final List<Float> mfEmbedding = generateRandomFloatList(100);
  private static final List<Float> companiesEmbedding = generateRandomFloatList(100);
  private static final List<Float> pymkCosineSimilarityEmbedding = generateRandomFloatList(100);

  private static final String valueSchemaForCompute = "{" +
      "  \"namespace\": \"example.compute\",    " +
      "  \"type\": \"record\",        " +
      "  \"name\": \"MemberFeature\",       " +
      "  \"fields\": [        " +
      "         { \"name\": \"id\", \"type\": \"string\" },             " +
      "         { \"name\": \"name\", \"type\": \"string\" },           " +
      "         {   \"default\": [], \"doc\": \"The member companies embedding\",\n" +
      "          \"name\": \"companiesEmbedding\",  \"type\": {\n" +
      "           \"items\": \"float\",  \"type\": \"array\"\n   }\n  }, " +
      "         { \"name\": \"member_feature\", \"type\": { \"type\": \"array\", \"items\": \"float\" } }        " +
      "  ]       " +
      " }       ";

  private static final String valueSchemaForCompute2 = "{" +
      "  \"namespace\": \"example.compute\",    " +
      "  \"type\": \"record\",        " +
      "  \"name\": \"MemberFeature\",       " +
      "  \"fields\": [        " +
      "         { \"name\": \"id\", \"type\": \"string\" },             " +
      "         { \"name\": \"name\", \"type\": \"string\" },           " +
      "         { \"name\": \"member_feature\", \"type\": { \"type\": \"array\", \"items\": \"float\" } }        " +
      "  ]       " +
      " }       ";

  @BeforeClass(alwaysRun = true)
  public void setUp() throws VeniceClientException {
    veniceCluster = ServiceFactory.getVeniceCluster(1, 1, 0, 2, 100, false, false);
    // Add one more server with fast-avro enabled
    Properties serverProperties = new Properties();
    serverProperties.put(ConfigKeys.SERVER_COMPUTE_FAST_AVRO_ENABLED, true);
    veniceCluster.addVeniceServer(new Properties(), serverProperties);

    // To trigger long-tail retry
    Properties routerProperties = new Properties();
    routerProperties.put(ConfigKeys.ROUTER_LONG_TAIL_RETRY_FOR_SINGLE_GET_THRESHOLD_MS, 1);
    routerProperties.put(ConfigKeys.ROUTER_LONG_TAIL_RETRY_FOR_BATCH_GET_THRESHOLD_MS, "1-:1");
    veniceCluster.addVeniceRouter(routerProperties);
    routerAddr = "http://" + veniceCluster.getVeniceRouters().get(0).getAddress();

    String keySchema = "\"int\"";

    // Create test store
    VersionCreationResponse creationResponse = veniceCluster.getNewStoreVersion(keySchema, valueSchemaForCompute);
    storeName = Version.parseStoreFromKafkaTopicName(creationResponse.getKafkaTopic());
    valueSchemaId = HelixReadOnlySchemaRepository.VALUE_SCHEMA_STARTING_ID;

    // TODO: Make serializers parameterized so we test them all.
    keySerializer = new VeniceAvroKafkaSerializer(keySchema);
    valueSerializer = new VeniceAvroKafkaSerializer(valueSchemaForCompute);
    valueSerializer2 = new VeniceAvroKafkaSerializer(valueSchemaForCompute2);
  }

  @AfterClass(alwaysRun = true)
  public void cleanUp() {
    if (veniceCluster != null) {
      veniceCluster.close();
    }
  }

  @Test
  public void testCompute() throws Exception {
    CompressionStrategy compressionStrategy = CompressionStrategy.NO_OP;
    BatchDeserializerType batchDeserializerType = BatchDeserializerType.BLOCKING;
    AvroGenericDeserializer.IterableImpl iterableImpl = AvroGenericDeserializer.IterableImpl.BLOCKING;
    boolean fastAvro = true;
    boolean valueLargerThan1MB = false;
    UpdateStoreQueryParams params = new UpdateStoreQueryParams();
    params.setCompressionStrategy(compressionStrategy);
    params.setReadComputationEnabled(true);
    params.setChunkingEnabled(valueLargerThan1MB);
    veniceCluster.updateStore(storeName, params);

    VersionCreationResponse newVersion = veniceCluster.getNewVersion(storeName, 1024);
    final int pushVersion = newVersion.getVersion();
    String topic = newVersion.getKafkaTopic();

    VeniceWriterFactory vwFactory =
        TestUtils.getVeniceTestWriterFactory(veniceCluster.getKafka().getAddress());
    try (VeniceWriter<Object, byte[], byte[]> veniceWriter =
        vwFactory.createVeniceWriter(topic, keySerializer, new DefaultSerializer(), valueLargerThan1MB);
        AvroGenericStoreClient<Integer, Object> storeClient = ClientFactory.getAndStartGenericAvroClient(
            ClientConfig.defaultGenericClientConfig(storeName)
                .setVeniceURL(routerAddr)
                .setBatchDeserializerType(batchDeserializerType)
                .setMultiGetEnvelopeIterableImpl(iterableImpl)
                .setUseFastAvro(fastAvro))) {

      pushSyntheticDataToStore(topic, 100, veniceCluster,
          veniceWriter, pushVersion, compressionStrategy, valueLargerThan1MB);

      Set<Integer> keySet = new HashSet<>();
      keySet.add(Integer.valueOf(1));
      keySet.add(Integer.valueOf(2));
      storeClient.compute()
          .cosineSimilarity("companiesEmbedding", pymkCosineSimilarityEmbedding, "companiesEmbedding_score")
          .cosineSimilarity("member_feature", pymkCosineSimilarityEmbedding, "member_feature_score")
          .execute(keySet).get();
      ControllerClient controllerClient = new ControllerClient(veniceCluster.getClusterName(), veniceCluster.getRandmonVeniceController().getControllerUrl());
      SchemaResponse schemaResponse = controllerClient.addValueSchema(storeName, valueSchemaForCompute2);
      veniceCluster.stopAndRestartVeniceServer(veniceCluster.getVeniceServers().get(0).getPort());

      VersionCreationResponse newVersion2 = veniceCluster.getNewVersion(storeName, 1024);
      final int pushVersion2 = newVersion2.getVersion();
      String topic2 = newVersion2.getKafkaTopic();
      VeniceWriter<Object, byte[], byte[]> veniceWriter2 = vwFactory.createVeniceWriter(topic2, keySerializer, new DefaultSerializer(), valueLargerThan1MB);
      pushSyntheticData2ToStore(topic2, 100, veniceCluster,
          veniceWriter2, pushVersion2, compressionStrategy, valueLargerThan1MB);
      veniceCluster.stopAndRestartVeniceServer(veniceCluster.getVeniceServers().get(0).getPort());

      Map<Integer, GenericRecord> computeResult = storeClient.compute()
          .cosineSimilarity("companiesEmbedding", pymkCosineSimilarityEmbedding, "companiesEmbedding_score")
          .cosineSimilarity("member_feature", pymkCosineSimilarityEmbedding, "member_feature_score")
          .execute(keySet).get();

      for (Map.Entry<Integer, GenericRecord> entry : computeResult.entrySet()) {
        Assert.assertEquals(((HashMap<String, String>)entry.getValue().get(VENICE_COMPUTATION_ERROR_MAP_FIELD_NAME)).size(), 1);
      }
    }
  }

  private void pushSyntheticDataToStore(String topic, int numOfRecords,
      VeniceClusterWrapper veniceCluster, VeniceWriter<Object, byte[], byte[]> veniceWriter,
      int pushVersion, CompressionStrategy compressionStrategy, boolean valueLargerThan1MB) throws Exception {
    veniceWriter.broadcastStartOfPush(false, valueLargerThan1MB, compressionStrategy, new HashMap<>());
    Schema valueSchema = Schema.parse(valueSchemaForCompute);
    // Insert test record and wait synchronously for it to succeed
    for (int i = 0; i < numOfRecords; ++i) {
      GenericRecord value = new GenericData.Record(valueSchema);
      value.put("id", valuePrefix + i);
      value.put("name", "companiesEmbedding");
      value.put("companiesEmbedding", companiesEmbedding);
      value.put("member_feature", mfEmbedding);
      byte[] compressedValue = CompressorFactory.getCompressor(compressionStrategy).compress(valueSerializer.serialize(topic, value));
      veniceWriter.put(Integer.valueOf(i), compressedValue, valueSchemaId).get();
    }
    // Write end of push message to make node become ONLINE from BOOTSTRAP
    veniceWriter.broadcastEndOfPush(new HashMap<>());

    // Wait for storage node to finish consuming, and new version to be activated
    String controllerUrl = veniceCluster.getAllControllersURLs();
    try (ControllerClient controllerClient = new ControllerClient(veniceCluster.getClusterName(), controllerUrl)) {
      TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, () -> {
        String status = controllerClient.queryJobStatus(topic).getStatus();
        if (status.equals(ExecutionStatus.ERROR.name())) {
          // Not recoverable (at least not without re-pushing), so not worth spinning our wheels until the timeout.
          throw new VeniceException("Push failed.");
        }

        int currentVersion = controllerClient.getStore(storeName).getStore().getCurrentVersion();
        // Refresh router metadata once new version is pushed, so that the router sees the latest store version.
        if (currentVersion == pushVersion) {
          veniceCluster.refreshAllRouterMetaData();
        }
        Assert.assertEquals(currentVersion, pushVersion, "New version not online yet.");
      });
    }
  }

  private void pushSyntheticData2ToStore(String topic, int numOfRecords,
      VeniceClusterWrapper veniceCluster, VeniceWriter<Object, byte[], byte[]> veniceWriter,
      int pushVersion, CompressionStrategy compressionStrategy, boolean valueLargerThan1MB) throws Exception {
    veniceWriter.broadcastStartOfPush(false, valueLargerThan1MB, compressionStrategy, new HashMap<>());
    Schema valueSchema = Schema.parse(valueSchemaForCompute2);
    // Insert test record and wait synchronously for it to succeed
    for (int i = 0; i < numOfRecords; ++i) {
      GenericRecord value = new GenericData.Record(valueSchema);
      value.put("id", valuePrefix + i);
      value.put("name", "companiesEmbedding");
      value.put("member_feature", mfEmbedding);
      byte[] compressedValue = CompressorFactory.getCompressor(compressionStrategy).compress(valueSerializer2.serialize(topic, value));
      veniceWriter.put(Integer.valueOf(i), compressedValue, 2).get();
    }
    // Write end of push message to make node become ONLINE from BOOTSTRAP
    veniceWriter.broadcastEndOfPush(new HashMap<>());

    // Wait for storage node to finish consuming, and new version to be activated
    String controllerUrl = veniceCluster.getAllControllersURLs();
    try (ControllerClient controllerClient = new ControllerClient(veniceCluster.getClusterName(), controllerUrl)) {
      TestUtils.waitForNonDeterministicAssertion(30, TimeUnit.SECONDS, true, () -> {
        String status = controllerClient.queryJobStatus(topic).getStatus();
        if (status.equals(ExecutionStatus.ERROR.name())) {
          // Not recoverable (at least not without re-pushing), so not worth spinning our wheels until the timeout.
          throw new VeniceException("Push failed.");
        }

        int currentVersion = controllerClient.getStore(storeName).getStore().getCurrentVersion();
        // Refresh router metadata once new version is pushed, so that the router sees the latest store version.
        if (currentVersion == pushVersion) {
          veniceCluster.refreshAllRouterMetaData();
        }
        Assert.assertEquals(currentVersion, pushVersion, "New version not online yet.");
      });
    }
  }

  private static List<Float> generateRandomFloatList(int listSize) {
    ThreadLocalRandom rand = ThreadLocalRandom.current();
    List<Float> feature = new ArrayList<>(listSize);
    for (int i = 0; i < listSize; i++) {
      feature.add(Float.valueOf(rand.nextFloat()));
    }
    return feature;
  }
}