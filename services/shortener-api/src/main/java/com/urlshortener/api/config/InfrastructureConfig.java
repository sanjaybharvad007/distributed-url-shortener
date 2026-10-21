package com.urlshortener.api.config;

import com.urlshortener.api.cache.RedisRing;
import com.urlshortener.api.id.IdGenerator;
import com.urlshortener.api.id.SnowflakeIdGenerator;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/** Wires the Redis ring, the DynamoDB client, the clock and the ID generator. */
@Configuration
public class InfrastructureConfig {

  private static final Logger log = LoggerFactory.getLogger(InfrastructureConfig.class);

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean(destroyMethod = "close")
  public RedisRing redisRing(AppProperties props) {
    AppProperties.Redis r = props.redis();
    return new RedisRing(r.nodes(), r.virtualNodes(), r.commandTimeout());
  }

  @Bean(destroyMethod = "close")
  public DynamoDbClient dynamoDbClient(AppProperties props) {
    AppProperties.DynamoDb d = props.dynamodb();
    DynamoDbClientBuilder b =
        DynamoDbClient.builder()
            .region(Region.of(d.region()))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .apiCallTimeout(d.callTimeout())
                    .apiCallAttemptTimeout(d.attemptTimeout())
                    .build());
    if (d.endpoint() != null && !d.endpoint().isBlank()) {
      b.endpointOverride(URI.create(d.endpoint()));
      log.info("DynamoDB endpoint override: {}", d.endpoint());
    }
    return b.build();
  }

  @Bean
  public IdGenerator idGenerator(AppProperties props, Clock clock) {
    long workerId = props.id().workerId();
    if (workerId < 0) {
      workerId = deriveWorkerId();
      log.info("APP_ID_WORKER_ID not set; derived worker id {} from hostname", workerId);
    }
    return new SnowflakeIdGenerator(workerId, props.id().epochMillis(), clock);
  }

  /**
   * Derives a worker ID in [0, 1023] from the hostname. Good enough for local / small fleets; in
   * production Terraform assigns distinct IDs per task slot through {@code APP_ID_WORKER_ID}.
   */
  static long deriveWorkerId() {
    try {
      String host = InetAddress.getLocalHost().getHostName();
      return Math.floorMod(host.hashCode(), (int) SnowflakeIdGenerator.MAX_WORKER_ID + 1);
    } catch (UnknownHostException e) {
      return 0;
    }
  }
}
