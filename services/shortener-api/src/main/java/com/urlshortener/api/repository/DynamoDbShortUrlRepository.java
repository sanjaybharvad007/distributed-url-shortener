package com.urlshortener.api.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.api.config.AppProperties;
import com.urlshortener.api.domain.Page;
import com.urlshortener.api.domain.ShortUrl;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * DynamoDB implementation of {@link ShortUrlRepository}.
 *
 * <p>Table: {@code short_urls} (PK {@code short_code}); GSIs {@code owner-index} ({@code owner_id},
 * {@code created_at}) and {@code url-hash-index} ({@code url_hash}, KEYS_ONLY). Every call is
 * wrapped in a Resilience4j retry + circuit breaker named {@code dynamodb}.
 */
@Repository
@CircuitBreaker(name = "dynamodb")
@Retry(name = "dynamodb")
public class DynamoDbShortUrlRepository implements ShortUrlRepository {

  private static final Logger log = LoggerFactory.getLogger(DynamoDbShortUrlRepository.class);
  private static final TypeReference<Map<String, String>> CURSOR_TYPE = new TypeReference<>() {};

  static final String ATTR_CODE = "short_code";
  static final String ATTR_URL = "long_url";
  static final String ATTR_URL_HASH = "url_hash";
  static final String ATTR_OWNER = "owner_id";
  static final String ATTR_CREATED = "created_at";
  static final String ATTR_EXPIRES = "expires_at";
  static final String ATTR_CLICKS = "click_count";
  static final String ATTR_STATUS = "status";
  static final String ATTR_SHARD = "shard_id";
  static final String OWNER_INDEX = "owner-index";
  static final String URL_HASH_INDEX = "url-hash-index";

  private final DynamoDbClient dynamo;
  private final String table;
  private final MeterRegistry registry;
  private final ObjectMapper mapper = new ObjectMapper();

  public DynamoDbShortUrlRepository(
      DynamoDbClient dynamo, AppProperties props, MeterRegistry registry) {
    this.dynamo = dynamo;
    this.table = props.dynamodb().table();
    this.registry = registry;
  }

  @Override
  public void insert(ShortUrl url) {
    timed(
        "PutItem",
        () -> {
          try {
            dynamo.putItem(
                PutItemRequest.builder()
                    .tableName(table)
                    .item(toItem(url))
                    .conditionExpression("attribute_not_exists(" + ATTR_CODE + ")")
                    .build());
          } catch (ConditionalCheckFailedException e) {
            throw new CodeAlreadyExistsException(url.shortCode());
          }
          return null;
        });
  }

  @Override
  public Optional<ShortUrl> findByCode(String code) {
    return timed(
        "GetItem",
        () -> {
          GetItemResponse r =
              dynamo.getItem(
                  GetItemRequest.builder()
                      .tableName(table)
                      .key(Map.of(ATTR_CODE, AttributeValue.fromS(code)))
                      .consistentRead(true)
                      .build());
          return r.hasItem() && !r.item().isEmpty()
              ? Optional.of(fromItem(r.item()))
              : Optional.empty();
        });
  }

  @Override
  public Optional<ShortUrl> findByUrlHash(String urlHash) {
    return timed(
        "Query.urlHash",
        () -> {
          QueryResponse r =
              dynamo.query(
                  QueryRequest.builder()
                      .tableName(table)
                      .indexName(URL_HASH_INDEX)
                      .keyConditionExpression(ATTR_URL_HASH + " = :h")
                      .expressionAttributeValues(Map.of(":h", AttributeValue.fromS(urlHash)))
                      .limit(1)
                      .build());
          if (r.count() == 0) {
            return Optional.empty();
          }
          // KEYS_ONLY projection: fetch the full item with a consistent read.
          String code = r.items().get(0).get(ATTR_CODE).s();
          return findByCode(code).filter(ShortUrl::isActive);
        });
  }

  @Override
  public Page<ShortUrl> findByOwner(String ownerId, int limit, String cursor) {
    return timed(
        "Query.owner",
        () -> {
          QueryRequest.Builder req =
              QueryRequest.builder()
                  .tableName(table)
                  .indexName(OWNER_INDEX)
                  .keyConditionExpression(ATTR_OWNER + " = :o")
                  .filterExpression("#s = :active")
                  .expressionAttributeNames(Map.of("#s", ATTR_STATUS))
                  .expressionAttributeValues(
                      Map.of(
                          ":o", AttributeValue.fromS(ownerId),
                          ":active", AttributeValue.fromS(ShortUrl.Status.ACTIVE.name())))
                  .scanIndexForward(false)
                  .limit(limit);
          if (cursor != null && !cursor.isBlank()) {
            req.exclusiveStartKey(decodeCursor(cursor));
          }
          QueryResponse r = dynamo.query(req.build());
          List<ShortUrl> items = new ArrayList<>(r.count());
          for (Map<String, AttributeValue> item : r.items()) {
            items.add(fromItem(item));
          }
          String next =
              r.hasLastEvaluatedKey() && !r.lastEvaluatedKey().isEmpty()
                  ? encodeCursor(r.lastEvaluatedKey())
                  : null;
          return new Page<>(items, next);
        });
  }

  @Override
  public boolean softDelete(String code, String ownerId) {
    return timed(
        "UpdateItem.delete",
        () -> {
          try {
            dynamo.updateItem(
                UpdateItemRequest.builder()
                    .tableName(table)
                    .key(Map.of(ATTR_CODE, AttributeValue.fromS(code)))
                    .updateExpression("SET #s = :deleted")
                    .conditionExpression(
                        "attribute_exists(" + ATTR_CODE + ") AND #s = :active AND #o = :owner")
                    .expressionAttributeNames(Map.of("#s", ATTR_STATUS, "#o", ATTR_OWNER))
                    .expressionAttributeValues(
                        Map.of(
                            ":deleted", AttributeValue.fromS(ShortUrl.Status.DELETED.name()),
                            ":active", AttributeValue.fromS(ShortUrl.Status.ACTIVE.name()),
                            ":owner", AttributeValue.fromS(ownerId)))
                    .build());
            return true;
          } catch (ConditionalCheckFailedException e) {
            return false;
          }
        });
  }

  @Override
  public void addClicks(Map<String, Long> deltasByCode) {
    for (Map.Entry<String, Long> e : deltasByCode.entrySet()) {
      timed(
          "UpdateItem.clicks",
          () -> {
            try {
              dynamo.updateItem(
                  UpdateItemRequest.builder()
                      .tableName(table)
                      .key(Map.of(ATTR_CODE, AttributeValue.fromS(e.getKey())))
                      .updateExpression("ADD " + ATTR_CLICKS + " :n")
                      .conditionExpression("attribute_exists(" + ATTR_CODE + ")")
                      .expressionAttributeValues(
                          Map.of(":n", AttributeValue.fromN(String.valueOf(e.getValue()))))
                      .build());
            } catch (ConditionalCheckFailedException ignored) {
              // Link was hard-deleted/expired by TTL between the click and the flush; drop it.
              log.debug("dropping {} clicks for vanished code {}", e.getValue(), e.getKey());
            }
            return null;
          });
    }
  }

  // ---------------------------------------------------------------- mapping

  static Map<String, AttributeValue> toItem(ShortUrl u) {
    Map<String, AttributeValue> item = new HashMap<>();
    item.put(ATTR_CODE, AttributeValue.fromS(u.shortCode()));
    item.put(ATTR_URL, AttributeValue.fromS(u.longUrl()));
    item.put(ATTR_URL_HASH, AttributeValue.fromS(u.urlHash()));
    item.put(ATTR_OWNER, AttributeValue.fromS(u.ownerId()));
    item.put(ATTR_CREATED, AttributeValue.fromN(String.valueOf(u.createdAt().toEpochMilli())));
    if (u.expiresAt() != null) {
      item.put(ATTR_EXPIRES, AttributeValue.fromN(String.valueOf(u.expiresAt().getEpochSecond())));
    }
    item.put(ATTR_CLICKS, AttributeValue.fromN(String.valueOf(u.clickCount())));
    item.put(ATTR_STATUS, AttributeValue.fromS(u.status().name()));
    item.put(ATTR_SHARD, AttributeValue.fromN(String.valueOf(u.shardId())));
    return item;
  }

  static ShortUrl fromItem(Map<String, AttributeValue> item) {
    AttributeValue expires = item.get(ATTR_EXPIRES);
    AttributeValue clicks = item.get(ATTR_CLICKS);
    AttributeValue shard = item.get(ATTR_SHARD);
    AttributeValue status = item.get(ATTR_STATUS);
    return new ShortUrl(
        item.get(ATTR_CODE).s(),
        item.get(ATTR_URL).s(),
        item.get(ATTR_URL_HASH).s(),
        item.get(ATTR_OWNER).s(),
        Instant.ofEpochMilli(Long.parseLong(item.get(ATTR_CREATED).n())),
        expires == null ? null : Instant.ofEpochSecond(Long.parseLong(expires.n())),
        clicks == null ? 0L : Long.parseLong(clicks.n()),
        status == null ? ShortUrl.Status.ACTIVE : ShortUrl.Status.valueOf(status.s()),
        shard == null ? -1 : Integer.parseInt(shard.n()));
  }

  private String encodeCursor(Map<String, AttributeValue> key) {
    Map<String, String> flat = new HashMap<>();
    key.forEach((k, v) -> flat.put(k, v.s() != null ? "S:" + v.s() : "N:" + v.n()));
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(flat));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Map<String, AttributeValue> decodeCursor(String cursor) {
    try {
      byte[] raw = Base64.getUrlDecoder().decode(cursor);
      Map<String, String> flat = mapper.readValue(raw, CURSOR_TYPE);
      Map<String, AttributeValue> key = new HashMap<>();
      flat.forEach(
          (k, v) ->
              key.put(
                  k,
                  v.startsWith("S:")
                      ? AttributeValue.fromS(v.substring(2))
                      : AttributeValue.fromN(v.substring(2))));
      return key;
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid cursor", e);
    }
  }

  private <T> T timed(String op, Supplier<T> call) {
    Timer.Sample sample = Timer.start(registry);
    String result = "ok";
    try {
      return call.get();
    } catch (CodeAlreadyExistsException e) {
      result = "conflict";
      throw e;
    } catch (RuntimeException e) {
      result = "error";
      throw e;
    } finally {
      sample.stop(
          Timer.builder("dynamodb_calls")
              .description("DynamoDB call latency by operation and result")
              .tag("op", op)
              .tag("result", result)
              .register(registry));
    }
  }
}
