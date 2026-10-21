# CloudWatch alarms complement the Prometheus rules with infrastructure-level signals
# (ALB, target health, DynamoDB throttling, ECS resources). All notify the on-call topic.

resource "aws_sns_topic" "oncall" {
  name = "${local.prefix}-oncall"
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alert_email != "" ? 1 : 0
  topic_arn = aws_sns_topic.oncall.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  alarm_actions = [aws_sns_topic.oncall.arn]
  tgs = {
    api        = aws_lb_target_group.api
    redirector = aws_lb_target_group.redirector
  }
}

# ALB 5xx ratio > 1% for 5 minutes (either targets or the ALB itself)
resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.prefix}-alb-5xx-ratio"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  datapoints_to_alarm = 3
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "ratio"
    expression  = "100 * (target5xx + elb5xx) / MAX([requests, 1])"
    label       = "5xx %"
    return_data = true
  }
  metric_query {
    id = "target5xx"
    metric {
      metric_name = "HTTPCode_Target_5XX_Count"
      namespace   = "AWS/ApplicationELB"
      period      = 60
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix }
    }
  }
  metric_query {
    id = "elb5xx"
    metric {
      metric_name = "HTTPCode_ELB_5XX_Count"
      namespace   = "AWS/ApplicationELB"
      period      = 60
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix }
    }
  }
  metric_query {
    id = "requests"
    metric {
      metric_name = "RequestCount"
      namespace   = "AWS/ApplicationELB"
      period      = 60
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix }
    }
  }
}

# Any unhealthy target behind either target group
resource "aws_cloudwatch_metric_alarm" "unhealthy_targets" {
  for_each            = local.tgs
  alarm_name          = "${local.prefix}-${each.key}-unhealthy-targets"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = each.value.arn_suffix
  }
}

# Redirect latency p99 at the ALB > 100 ms
resource "aws_cloudwatch_metric_alarm" "redirect_latency" {
  alarm_name          = "${local.prefix}-redirector-p99-latency"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  extended_statistic  = "p99"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 3
  threshold           = 0.1
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.redirector.arn_suffix
  }
}

# DynamoDB throttling or system errors
resource "aws_cloudwatch_metric_alarm" "dynamodb_throttles" {
  alarm_name          = "${local.prefix}-dynamodb-throttles"
  namespace           = "AWS/DynamoDB"
  metric_name         = "ThrottledRequests"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  dimensions          = { TableName = aws_dynamodb_table.short_urls.name }
}

resource "aws_cloudwatch_metric_alarm" "dynamodb_system_errors" {
  alarm_name          = "${local.prefix}-dynamodb-system-errors"
  namespace           = "AWS/DynamoDB"
  metric_name         = "SystemErrors"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  dimensions          = { TableName = aws_dynamodb_table.short_urls.name }
}

# ECS CPU / memory per service
resource "aws_cloudwatch_metric_alarm" "ecs_cpu" {
  for_each            = { api = aws_ecs_service.api.name, redirector = aws_ecs_service.redirector.name }
  alarm_name          = "${local.prefix}-${each.key}-cpu-high"
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 10
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = local.alarm_actions
  dimensions          = { ClusterName = aws_ecs_cluster.main.name, ServiceName = each.value }
}

resource "aws_cloudwatch_metric_alarm" "ecs_memory" {
  for_each            = { api = aws_ecs_service.api.name, redirector = aws_ecs_service.redirector.name }
  alarm_name          = "${local.prefix}-${each.key}-memory-high"
  namespace           = "AWS/ECS"
  metric_name         = "MemoryUtilization"
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 10
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = local.alarm_actions
  dimensions          = { ClusterName = aws_ecs_cluster.main.name, ServiceName = each.value }
}

# ElastiCache shard health
resource "aws_cloudwatch_metric_alarm" "redis_memory" {
  count               = var.redis_shard_count
  alarm_name          = "${local.prefix}-redis-shard-${count.index}-memory"
  namespace           = "AWS/ElastiCache"
  metric_name         = "DatabaseMemoryUsagePercentage"
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 5
  threshold           = 90
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = local.alarm_actions
  dimensions          = { ReplicationGroupId = aws_elasticache_replication_group.shard[count.index].id }
}

resource "aws_cloudwatch_metric_alarm" "redis_evictions" {
  count               = var.redis_shard_count
  alarm_name          = "${local.prefix}-redis-shard-${count.index}-evictions"
  namespace           = "AWS/ElastiCache"
  metric_name         = "Evictions"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 2
  threshold           = 1000
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  dimensions          = { ReplicationGroupId = aws_elasticache_replication_group.shard[count.index].id }
}
