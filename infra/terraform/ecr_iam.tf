# ---------------------------------------------------------------- ECR
resource "aws_ecr_repository" "api" {
  name                 = "${var.name}/shortener-api"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  force_delete = var.environment != "prod"
}

resource "aws_ecr_repository" "redirector" {
  name                 = "${var.name}/redirector"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  force_delete = var.environment != "prod"
}

resource "aws_ecr_lifecycle_policy" "keep_recent" {
  for_each   = { api = aws_ecr_repository.api.name, redirector = aws_ecr_repository.redirector.name }
  repository = each.value
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep last 30 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 30 }
      action       = { type = "expire" }
    }]
  })
}

# ---------------------------------------------------------------- secrets
resource "aws_secretsmanager_secret" "api_keys" {
  name                    = "${local.prefix}/api-keys"
  recovery_window_in_days = var.environment == "prod" ? 30 : 0
}

resource "aws_secretsmanager_secret_version" "api_keys" {
  secret_id     = aws_secretsmanager_secret.api_keys.id
  secret_string = var.api_keys
}

# ---------------------------------------------------------------- ECS task execution role (pull images, write logs, read secrets)
data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "task_execution" {
  name               = "${local.prefix}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "task_execution_secrets" {
  name = "read-secrets"
  role = aws_iam_role.task_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [aws_secretsmanager_secret.api_keys.arn]
    }]
  })
}

# ---------------------------------------------------------------- task roles (least privilege per service)
resource "aws_iam_role" "api_task" {
  name               = "${local.prefix}-api-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "api_task_dynamodb" {
  name = "dynamodb-rw"
  role = aws_iam_role.api_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem",
        "dynamodb:Query", "dynamodb:DescribeTable"
      ]
      Resource = [aws_dynamodb_table.short_urls.arn, "${aws_dynamodb_table.short_urls.arn}/index/*"]
    }]
  })
}

resource "aws_iam_role" "redirector_task" {
  name               = "${local.prefix}-redirector-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "redirector_task_dynamodb" {
  name = "dynamodb-ro"
  role = aws_iam_role.redirector_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["dynamodb:GetItem", "dynamodb:DescribeTable"]
      Resource = [aws_dynamodb_table.short_urls.arn]
    }]
  })
}

# ---------------------------------------------------------------- GitHub Actions OIDC deploy role (no long-lived keys)
data "aws_caller_identity" "current" {}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

data "aws_iam_policy_document" "github_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main", "repo:${var.github_repository}:environment:*"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${local.prefix}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_assume.json
}

resource "aws_iam_role_policy" "github_deploy" {
  name = "deploy"
  role = aws_iam_role.github_deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:CompleteLayerUpload", "ecr:InitiateLayerUpload",
          "ecr:PutImage", "ecr:UploadLayerPart", "ecr:BatchGetImage", "ecr:DescribeImages"
        ]
        Resource = [aws_ecr_repository.api.arn, aws_ecr_repository.redirector.arn]
      },
      {
        Effect = "Allow"
        Action = [
          "ecs:DescribeServices", "ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition",
          "ecs:UpdateService", "ecs:DescribeTasks", "ecs:ListTasks"
        ]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = [aws_iam_role.task_execution.arn, aws_iam_role.api_task.arn, aws_iam_role.redirector_task.arn]
      }
    ]
  })
}
