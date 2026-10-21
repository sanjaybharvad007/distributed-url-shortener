package com.urlshortener.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.urlshortener.api.cache.RedisKeys;
import com.urlshortener.api.cache.RedisRing;
import com.urlshortener.api.web.dto.UrlResponse;
import com.urlshortener.api.web.dto.UrlStatsResponse;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Full-stack integration test: real Spring context, three Redis shards and DynamoDB Local in
 * Docker. Exercises create → redirect → write-behind click counting → delete → negative cache.
 */
@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShortenerApiIT {

  private static final DockerImageName REDIS = DockerImageName.parse("redis:7.4-alpine");
  private static final DockerImageName DYNAMO =
      DockerImageName.parse("amazon/dynamodb-local:2.5.2");
  private static final String API_KEY = "it-key";
  private static final String BURST_KEY = "burst-key"; // separate bucket so bursts don't bleed

  @Container
  static final GenericContainer<?> redis1 = new GenericContainer<>(REDIS).withExposedPorts(6379);

  @Container
  static final GenericContainer<?> redis2 = new GenericContainer<>(REDIS).withExposedPorts(6379);

  @Container
  static final GenericContainer<?> redis3 = new GenericContainer<>(REDIS).withExposedPorts(6379);

  @Container
  static final GenericContainer<?> dynamo =
      new GenericContainer<>(DYNAMO)
          .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
          .withExposedPorts(8000);

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("app.redis.nodes", () -> String.join(",", node(redis1), node(redis2), node(redis3)));
    r.add("app.dynamodb.endpoint", ShortenerApiIT::dynamoEndpoint);
    r.add("app.api-keys", () -> API_KEY + "," + BURST_KEY);
    r.add("app.id.worker-id", () -> "7");
    r.add("app.redis.negative-ttl", () -> "2s");
    r.add("management.tracing.enabled", () -> "false");
  }

  private static String node(GenericContainer<?> c) {
    return c.getHost() + ":" + c.getMappedPort(6379);
  }

  private static String dynamoEndpoint() {
    return "http://" + dynamo.getHost() + ":" + dynamo.getMappedPort(8000);
  }

  @BeforeAll
  static void createTable() {
    System.setProperty("aws.accessKeyId", "local");
    System.setProperty("aws.secretAccessKey", "local");
    try (DynamoDbClient ddb =
        DynamoDbClient.builder()
            .region(Region.US_WEST_2)
            .endpointOverride(URI.create(dynamoEndpoint()))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()) {
      ddb.createTable(
          CreateTableRequest.builder()
              .tableName("short_urls")
              .billingMode(BillingMode.PAY_PER_REQUEST)
              .attributeDefinitions(
                  attr("short_code", ScalarAttributeType.S),
                  attr("owner_id", ScalarAttributeType.S),
                  attr("created_at", ScalarAttributeType.N),
                  attr("url_hash", ScalarAttributeType.S))
              .keySchema(key("short_code", KeyType.HASH))
              .globalSecondaryIndexes(
                  GlobalSecondaryIndex.builder()
                      .indexName("owner-index")
                      .keySchema(key("owner_id", KeyType.HASH), key("created_at", KeyType.RANGE))
                      .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                      .build(),
                  GlobalSecondaryIndex.builder()
                      .indexName("url-hash-index")
                      .keySchema(key("url_hash", KeyType.HASH))
                      .projection(
                          Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                      .build())
              .build());
    }
  }

  private static AttributeDefinition attr(String name, ScalarAttributeType type) {
    return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
  }

  private static KeySchemaElement key(String name, KeyType type) {
    return KeySchemaElement.builder().attributeName(name).keyType(type).build();
  }

  @LocalServerPort int port;
  @Autowired RedisRing ring;

  /** A client that reports redirects instead of following them and never throws on 4xx/5xx. */
  private final TestRestTemplate rest =
      new TestRestTemplate(
          new RestTemplateBuilder()
              .requestFactory(
                  () ->
                      new SimpleClientHttpRequestFactory() {
                        @Override
                        protected void prepareConnection(HttpURLConnection c, String method)
                            throws IOException {
                          super.prepareConnection(c, method);
                          c.setInstanceFollowRedirects(false);
                        }
                      }));

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private HttpHeaders authed() {
    HttpHeaders h = new HttpHeaders();
    h.set("X-API-Key", API_KEY);
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  @Test
  void fullLifecycle() {
    String target = "https://example.com/it/" + System.nanoTime();

    // create
    ResponseEntity<UrlResponse> created =
        rest.postForEntity(
            url("/api/v1/urls"),
            new HttpEntity<>(Map.of("longUrl", target, "ttlSeconds", 3600), authed()),
            UrlResponse.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String code = created.getBody().shortCode();
    assertThat(code).matches("[0-9A-Za-z]{4,32}");
    assertThat(created.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("40");

    // write-through: the owning shard (and only that shard) has the key
    String key = RedisKeys.url(code);
    int owner = ring.shardFor(key);
    assertThat(ring.shard(owner).get(key)).isEqualTo(target);
    for (int i = 0; i < ring.size(); i++) {
      if (i != owner) {
        assertThat(ring.shard(i).get(key)).isNull();
      }
    }

    // idempotent create returns the same code with 200
    ResponseEntity<UrlResponse> again =
        rest.postForEntity(
            url("/api/v1/urls"),
            new HttpEntity<>(Map.of("longUrl", target), authed()),
            UrlResponse.class);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(again.getBody().shortCode()).isEqualTo(code);

    // redirect (fallback path served by the Java service)
    ResponseEntity<Void> redirect = rest.getForEntity(url("/" + code), Void.class);
    assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(redirect.getHeaders().getLocation()).isEqualTo(URI.create(target));

    // write-behind click counting lands in DynamoDB
    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(
            () -> {
              ResponseEntity<UrlStatsResponse> stats =
                  rest.exchange(
                      url("/api/v1/urls/" + code + "/stats"),
                      HttpMethod.GET,
                      new HttpEntity<>(authed()),
                      UrlStatsResponse.class);
              assertThat(stats.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(stats.getBody().clickCount()).isGreaterThanOrEqualTo(1);
            });

    // list
    ResponseEntity<String> list =
        rest.exchange(
            url("/api/v1/urls?limit=10"), HttpMethod.GET, new HttpEntity<>(authed()), String.class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).contains(code);

    // delete → tombstone, negative cache entry, redirect 404
    ResponseEntity<Void> deleted =
        rest.exchange(
            url("/api/v1/urls/" + code), HttpMethod.DELETE, new HttpEntity<>(authed()), Void.class);
    assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(ring.shard(owner).get(key)).isEqualTo(RedisKeys.NEGATIVE);
    assertThat(rest.getForEntity(url("/" + code), Void.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            rest.exchange(
                    url("/api/v1/urls/" + code),
                    HttpMethod.GET,
                    new HttpEntity<>(authed()),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void customAliasIsReservedExactlyOnce() {
    String alias = "it" + Long.toString(System.nanoTime(), 36);
    ResponseEntity<UrlResponse> first =
        rest.postForEntity(
            url("/api/v1/urls"),
            new HttpEntity<>(
                Map.of("longUrl", "https://example.com/alias", "customAlias", alias), authed()),
            UrlResponse.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(first.getBody().shortCode()).isEqualTo(alias);

    ResponseEntity<String> second =
        rest.postForEntity(
            url("/api/v1/urls"),
            new HttpEntity<>(
                Map.of("longUrl", "https://example.com/other", "customAlias", alias), authed()),
            String.class);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void rateLimiterEventuallyRejectsBursts() {
    // Burst is 40 at 20 rps. Fire 120 requests concurrently so refill during the test cannot
    // mask the limit; at least one must be rejected and none may fail open.
    HttpHeaders burst = new HttpHeaders();
    burst.set("X-API-Key", BURST_KEY);
    List<HttpStatus> seen = Collections.synchronizedList(new ArrayList<>());
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 120; i++) {
        pool.submit(
            () -> {
              ResponseEntity<String> r =
                  rest.exchange(
                      url("/api/v1/urls/doesnotexist1"),
                      HttpMethod.GET,
                      new HttpEntity<>(burst),
                      String.class);
              seen.add(HttpStatus.valueOf(r.getStatusCode().value()));
            });
      }
    }
    assertThat(seen).hasSize(120);
    assertThat(seen).contains(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(seen.stream().filter(s -> s == HttpStatus.TOO_MANY_REQUESTS).count())
        .isGreaterThanOrEqualTo(120 - 40 - 10); // tolerate a little refill during the burst
  }

  @Test
  void unauthenticatedCallsAreRejected() {
    ResponseEntity<String> r = rest.getForEntity(url("/api/v1/urls/abcd123"), String.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void readinessProbeReportsDependencies() {
    ResponseEntity<String> r = rest.getForEntity(url("/actuator/health/readiness"), String.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(r.getBody()).contains("\"status\":\"UP\"");
  }
}
