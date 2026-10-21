# Terraform: AWS infrastructure

| File | Resources |
| --- | --- |
| `network.tf` | VPC, public/private subnets across AZs, NAT, DynamoDB gateway endpoint, security groups |
| `dynamodb.tf` | `short_urls` table, GSIs, TTL, PITR, encryption |
| `redis.tf` | N independent Multi-AZ ElastiCache replication groups (the consistent-hash ring shards) |
| `ecr_iam.tf` | ECR repos (immutable tags, scan on push), Secrets Manager, task/execution roles, GitHub OIDC deploy role |
| `ecs.tf` | ECS Fargate cluster, ALB + listeners/rules, task definitions, services with circuit-breaker rollback, autoscaling |
| `alarms.tf` | SNS on-call topic, CloudWatch alarms (ALB 5xx/latency/unhealthy targets, DynamoDB throttles, ECS CPU/memory, Redis memory/evictions) |

```bash
terraform init
terraform plan  -var-file=example.tfvars -var "api_keys=$API_KEYS"
terraform apply -var-file=example.tfvars -var "api_keys=$API_KEYS"
```

First apply creates the ECR repositories; push images (or let the `deploy` workflow do it), then re-apply
with `api_image_tag` / `redirector_image_tag` set to the pushed tag.

Rough monthly cost for the defaults in `dev` (2 AZ, 1 NAT, 3× `cache.t4g.small` ×2 nodes, 5 Fargate tasks,
on-demand DynamoDB at low volume): ~US$250. The `prod` example with `cache.r7g.large` is ~US$900 before traffic.
