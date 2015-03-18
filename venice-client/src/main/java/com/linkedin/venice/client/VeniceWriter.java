package com.linkedin.venice.client;

import com.linkedin.venice.config.GlobalConfiguration;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.kafka.producer.KafkaProducer;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.message.KafkaValue;
import com.linkedin.venice.message.OperationType;
import com.linkedin.venice.serialization.Serializer;
import com.linkedin.venice.utils.Props;
import org.apache.log4j.Logger;


/**
 * Class which acts as the primary writer API
 */
public class VeniceWriter<K, V> {

  // log4j logger
  static final Logger logger = Logger.getLogger(VeniceWriter.class.getName());

  private final KafkaProducer kp;

  private Props props;
  private final String kafkaBrokerUrl;
  private final String storeName;
  private final Serializer<K> keySerializer;
  private final Serializer<V> valueSerializer;

  public VeniceWriter(Props props, String storeName, Serializer<K> keySerializer, Serializer<V> valueSerializer) {

    // TODO: Deprecate/refactor the config. It's really not needed for the most part
    try {
      GlobalConfiguration.initializeFromFile("./config/config.properties");
      this.props = props;
      this.kafkaBrokerUrl = props.getString("kafka.broker.url", "localhost:9092");
      this.storeName = storeName;
      this.keySerializer = keySerializer;
      this.valueSerializer = valueSerializer;
    } catch (Exception e) {
      logger.error("Error while starting up configuration for VeniceWriter.", e);
      throw new VeniceException("Error while starting up configuration for VeniceWriter", e);
    }

    kp = new KafkaProducer();
  }

  /**
   * Execute a standard "delete" on the key.
   * @param key - The key to delete in storage.
   * */
  public void delete(K key) {

    KafkaKey kafkaKey = new KafkaKey(keySerializer.toBytes(key));
    KafkaValue kafkaValue = new KafkaValue(OperationType.DELETE);
    kp.sendMessage(storeName, kafkaKey, kafkaValue);
  }

  /**
   * Execute a standard "put" on the key.
   * @param key - The key to put in storage.
   * @param value - The value to be associated with the given key
   * */
  public void put(K key, V value) {

    KafkaKey kafkaKey = new KafkaKey(keySerializer.toBytes(key));
    KafkaValue kafkaValue = new KafkaValue(OperationType.PUT, valueSerializer.toBytes(value));
    kp.sendMessage(storeName, kafkaKey, kafkaValue);
  }

  /**
   * Execute a standard "partial put" on the key.
   * @param key - The key to put in storage.
   * @param value - The value to be associated with the given key
   * */
  public void putPartial(K key, V value) {

    throw new UnsupportedOperationException("Partial put is not supported yet.");
  }
}
