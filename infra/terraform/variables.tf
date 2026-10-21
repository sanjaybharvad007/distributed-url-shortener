variable "region" {
  description = "AWS region"
  type        = string
  default     = "us-west-2"
}

variable "environment" {
  description = "Deployment environment name (dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "name" {
  description = "Base name for all resources"
  type        = string
  default     = "url-shortener"
}

variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
}

variable "az_count" {
  description = "Number of availability zones to span"
  type        = number
  default     = 2
}

# ---------------------------------------------------------------- images
variable "api_image_tag" {
  description = "shortener-api image tag in ECR (CD sets this to the git SHA)"
  type        = string
  default     = "latest"
}

variable "redirector_image_tag" {
  description = "redirector image tag in ECR"
  type        = string
  default     = "latest"
}

# ---------------------------------------------------------------- capacity
variable "api_cpu" {
  type    = number
  default = 1024
}

variable "api_memory" {
  type    = number
  default = 2048
}

variable "api_desired_count" {
  type    = number
  default = 2
}

variable "api_max_count" {
  type    = number
  default = 10
}

variable "redirector_cpu" {
  type    = number
  default = 512
}

variable "redirector_memory" {
  type    = number
  default = 1024
}

variable "redirector_desired_count" {
  type    = number
  default = 3
}

variable "redirector_max_count" {
  type    = number
  default = 30
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "redis_shard_count" {
  description = "Number of independent Redis replication groups forming the consistent-hash ring"
  type        = number
  default     = 3
}

# ---------------------------------------------------------------- app
variable "api_keys" {
  description = "Comma-separated API keys for the management API (stored in Secrets Manager)"
  type        = string
  sensitive   = true
  default     = "change-me"
}

variable "alert_email" {
  description = "Email address subscribed to the on-call SNS topic (empty = no subscription)"
  type        = string
  default     = ""
}

variable "github_repository" {
  description = "GitHub repo (owner/name) allowed to assume the deploy role via OIDC"
  type        = string
  default     = "sanjaybharvad007/distributed-url-shortener"
}

variable "domain_name" {
  description = "Optional custom domain for the ALB (leave empty to use the ALB DNS name over HTTP)"
  type        = string
  default     = ""
}

variable "certificate_arn" {
  description = "ACM certificate ARN for HTTPS (required when domain_name is set)"
  type        = string
  default     = ""
}
