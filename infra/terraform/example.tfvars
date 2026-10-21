region            = "us-west-2"
environment       = "prod"
github_repository = "sanjaybharvad007/distributed-url-shortener"
alert_email       = "oncall@example.com"

# Capacity
redis_shard_count        = 3
redis_node_type          = "cache.r7g.large"
redirector_desired_count = 3
redirector_max_count     = 30
api_desired_count        = 2
api_max_count            = 10

# HTTPS (optional)
# domain_name     = "sho.rt"
# certificate_arn = "arn:aws:acm:us-west-2:123456789012:certificate/..."

# Set via -var or TF_VAR_api_keys rather than committing:
# api_keys = "key1,key2"
