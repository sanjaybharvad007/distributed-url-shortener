# Each shard is an independent Multi-AZ replication group (primary + replica).
# The services place keys on shards client-side with the consistent-hash ring,
# so these are deliberately NOT a single cluster-mode group (see ADR 0002).
resource "aws_elasticache_subnet_group" "redis" {
  name       = "${local.prefix}-redis"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_elasticache_parameter_group" "redis" {
  name   = "${local.prefix}-redis7"
  family = "redis7"

  parameter {
    name  = "maxmemory-policy"
    value = "allkeys-lru"
  }
}

resource "aws_elasticache_replication_group" "shard" {
  count = var.redis_shard_count

  replication_group_id = "${local.prefix}-shard-${count.index}"
  description          = "URL shortener cache ring shard ${count.index}"
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = var.redis_node_type
  num_cache_clusters   = 2
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.redis.name
  subnet_group_name    = aws_elasticache_subnet_group.redis.name
  security_group_ids   = [aws_security_group.redis.id]

  automatic_failover_enabled = true
  multi_az_enabled           = true
  at_rest_encryption_enabled = true
  transit_encryption_enabled = false # keep plain redis:// for the client-side ring; enable + use rediss:// if required
  apply_immediately          = var.environment != "prod"

  tags = { Name = "${local.prefix}-shard-${count.index}", Shard = tostring(count.index) }
}

locals {
  # Ordered node list; ORDER MATTERS — both services build the ring from it.
  redis_nodes = join(",", [
    for rg in aws_elasticache_replication_group.shard : "${rg.primary_endpoint_address}:6379"
  ])
}
