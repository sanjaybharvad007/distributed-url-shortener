output "alb_dns_name" {
  description = "Public entry point"
  value       = aws_lb.main.dns_name
}

output "base_url" {
  value = local.base_url
}

output "ecr_api_repository" {
  value = aws_ecr_repository.api.repository_url
}

output "ecr_redirector_repository" {
  value = aws_ecr_repository.redirector.repository_url
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "github_deploy_role_arn" {
  description = "Set as the AWS_DEPLOY_ROLE_ARN repository secret for the deploy workflow"
  value       = aws_iam_role.github_deploy.arn
}

output "redis_nodes" {
  description = "Ordered ring membership shared by both services"
  value       = local.redis_nodes
}

output "oncall_topic_arn" {
  value = aws_sns_topic.oncall.arn
}
