# ---------------------------------------------------------------- cluster + logs
resource "aws_ecs_cluster" "main" {
  name = local.prefix
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${local.prefix}/shortener-api"
  retention_in_days = 30
}

resource "aws_cloudwatch_log_group" "redirector" {
  name              = "/ecs/${local.prefix}/redirector"
  retention_in_days = 30
}

# ---------------------------------------------------------------- load balancer
resource "aws_lb" "main" {
  name                       = local.prefix
  load_balancer_type         = "application"
  subnets                    = aws_subnet.public[*].id
  security_groups            = [aws_security_group.alb.id]
  idle_timeout               = 60
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "api" {
  name                 = "${local.prefix}-api"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 20

  health_check {
    path                = "/actuator/health/readiness"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }
}

resource "aws_lb_target_group" "redirector" {
  name                 = "${local.prefix}-redirector"
  port                 = 8081
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 10

  health_check {
    path                = "/readyz"
    interval            = 10
    timeout             = 3
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }
}

locals {
  https = var.certificate_arn != ""
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  # With a certificate: redirect to HTTPS. Without: serve the redirector directly.
  dynamic "default_action" {
    for_each = local.https ? [1] : []
    content {
      type = "redirect"
      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
  dynamic "default_action" {
    for_each = local.https ? [] : [1]
    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.redirector.arn
    }
  }
}

resource "aws_lb_listener" "https" {
  count             = local.https ? 1 : 0
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.redirector.arn
  }
}

# /api/*, /actuator/*, /swagger-ui/* and /v3/* go to the Java API; everything else is a redirect.
resource "aws_lb_listener_rule" "api" {
  listener_arn = local.https ? aws_lb_listener.https[0].arn : aws_lb_listener.http.arn
  priority     = 10

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
  condition {
    path_pattern {
      values = ["/api/*", "/swagger-ui*", "/v3/*", "/actuator/health*"]
    }
  }
}

# ---------------------------------------------------------------- task definitions
locals {
  base_url = var.domain_name != "" ? "https://${var.domain_name}" : "http://${aws_lb.main.dns_name}"

  common_env = [
    { name = "AWS_REGION", value = var.region },
    { name = "OTEL_EXPORTER_OTLP_ENDPOINT", value = "" }, # point at an ADOT sidecar / collector when tracing is wired up
  ]
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.prefix}-shortener-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.api_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name                   = "shortener-api"
    image                  = "${aws_ecr_repository.api.repository_url}:${var.api_image_tag}"
    essential              = true
    portMappings           = [{ containerPort = 8080, protocol = "tcp" }]
    readonlyRootFilesystem = true
    environment = concat(local.common_env, [
      { name = "APP_REDIS_NODES", value = local.redis_nodes },
      { name = "APP_BASE_URL", value = local.base_url },
      { name = "APP_ID_WORKER_ID", value = "-1" }, # derived from the task hostname; see ADR 0001
      { name = "SPRING_PROFILES_ACTIVE", value = var.environment },
      { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp" },
    ])
    secrets     = [{ name = "APP_API_KEYS", valueFrom = aws_secretsmanager_secret.api_keys.arn }]
    mountPoints = [{ sourceVolume = "tmp", containerPath = "/tmp" }]
    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health/liveness || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.api.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "api"
      }
    }
  }])

  volume {
    name = "tmp"
  }
}

resource "aws_ecs_task_definition" "redirector" {
  family                   = "${local.prefix}-redirector"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.redirector_cpu
  memory                   = var.redirector_memory
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.redirector_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name                   = "redirector"
    image                  = "${aws_ecr_repository.redirector.repository_url}:${var.redirector_image_tag}"
    essential              = true
    portMappings           = [{ containerPort = 8081, protocol = "tcp" }]
    readonlyRootFilesystem = true
    environment = concat(local.common_env, [
      { name = "REDIRECTOR_ADDR", value = ":8081" },
      { name = "REDIRECTOR_REDIS_NODES", value = local.redis_nodes },
      { name = "REDIRECTOR_DYNAMODB_TABLE", value = aws_dynamodb_table.short_urls.name },
      { name = "REDIRECTOR_TRUST_PROXY", value = "true" },
    ])
    healthCheck = {
      command     = ["CMD", "/redirector", "-healthcheck"]
      interval    = 10
      timeout     = 3
      retries     = 3
      startPeriod = 5
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.redirector.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "redirector"
      }
    }
  }])
}

# ---------------------------------------------------------------- services
resource "aws_ecs_service" "api" {
  name            = "shortener-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count
  launch_type     = "FARGATE"

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = 90
  enable_execute_command             = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.service.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "shortener-api"
    container_port   = 8080
  }

  lifecycle {
    ignore_changes = [desired_count] # managed by autoscaling
  }

  depends_on = [aws_lb_listener.http]
}

resource "aws_ecs_service" "redirector" {
  name            = "redirector"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.redirector.arn
  desired_count   = var.redirector_desired_count
  launch_type     = "FARGATE"

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = 20
  enable_execute_command             = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.service.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.redirector.arn
    container_name   = "redirector"
    container_port   = 8081
  }

  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener.http]
}

# ---------------------------------------------------------------- autoscaling
locals {
  scaled = {
    api        = { service = aws_ecs_service.api.name, min = var.api_desired_count, max = var.api_max_count, tg = aws_lb_target_group.api }
    redirector = { service = aws_ecs_service.redirector.name, min = var.redirector_desired_count, max = var.redirector_max_count, tg = aws_lb_target_group.redirector }
  }
}

resource "aws_appautoscaling_target" "svc" {
  for_each           = local.scaled
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${each.value.service}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = each.value.min
  max_capacity       = each.value.max
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each           = local.scaled
  name               = "${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.svc[each.key].service_namespace
  resource_id        = aws_appautoscaling_target.svc[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.svc[each.key].scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 120
    scale_out_cooldown = 30
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

resource "aws_appautoscaling_policy" "requests" {
  for_each           = local.scaled
  name               = "${each.key}-requests"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.svc[each.key].service_namespace
  resource_id        = aws_appautoscaling_target.svc[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.svc[each.key].scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = each.key == "redirector" ? 5000 : 500 # requests per target per minute
    scale_in_cooldown  = 120
    scale_out_cooldown = 30
    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.main.arn_suffix}/${each.value.tg.arn_suffix}"
    }
  }
}
