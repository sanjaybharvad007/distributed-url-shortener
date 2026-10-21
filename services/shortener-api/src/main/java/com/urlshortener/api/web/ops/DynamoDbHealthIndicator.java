package com.urlshortener.api.web.ops;

import com.urlshortener.api.config.AppProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

@Component // bean "dynamoDbHealthIndicator" -> health contributor "dynamoDb"
public class DynamoDbHealthIndicator implements HealthIndicator {

  private final DynamoDbClient dynamo;
  private final String table;

  public DynamoDbHealthIndicator(DynamoDbClient dynamo, AppProperties props) {
    this.dynamo = dynamo;
    this.table = props.dynamodb().table();
  }

  @Override
  public Health health() {
    try {
      TableStatus status =
          dynamo
              .describeTable(DescribeTableRequest.builder().tableName(table).build())
              .table()
              .tableStatus();
      boolean ok = status == TableStatus.ACTIVE || status == TableStatus.UPDATING;
      return (ok ? Health.up() : Health.down())
          .withDetail("table", table)
          .withDetail("status", status.toString())
          .build();
    } catch (RuntimeException e) {
      return Health.down(e).withDetail("table", table).build();
    }
  }
}
