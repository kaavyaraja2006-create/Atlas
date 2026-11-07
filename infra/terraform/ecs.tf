resource "aws_ecs_cluster" "main" {
  name = var.project
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "app" {
  for_each          = toset(["api", "web"])
  name              = "/${var.project}/${each.key}"
  retention_in_days = 365
  kms_key_id        = aws_kms_key.main.arn
}

locals {
  db_secret = aws_db_instance.main.master_user_secret[0].secret_arn

  api_environment = [
    { name = "SERVER_PORT", value = "8081" }, # only the web tier in the same task reaches it
    { name = "ATLAS_DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/atlas?sslmode=require" },
    { name = "ATLAS_COOKIE_SECURE", value = "true" },
    { name = "ATLAS_FORWARD_HEADERS", value = "framework" }, # the web tier passes one clean client address
    { name = "ATLAS_METRICS_USERNAME", value = "prometheus" },
    { name = "ATLAS_ANCHOR_ENABLED", value = tostring(var.anchoring_enabled) },
    { name = "ATLAS_ANCHOR_DIR", value = "/anchors" },
    { name = "ATLAS_ANCHOR_KEY_ID", value = "${var.project}-1" },
    { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=60" },
  ]

  api_secrets = concat([
    { name = "ATLAS_DB_USER", valueFrom = "${local.db_secret}:username::" },
    { name = "ATLAS_DB_PASSWORD", valueFrom = "${local.db_secret}:password::" },
    { name = "ATLAS_JWT_SECRET", valueFrom = aws_secretsmanager_secret.jwt.arn },
    { name = "ATLAS_METRICS_PASSWORD", valueFrom = aws_secretsmanager_secret.metrics_password.arn },
    ], var.anchoring_enabled ? [
    { name = "ATLAS_ANCHOR_PRIVATE_KEY", valueFrom = aws_secretsmanager_secret.anchor_private_key.arn },
    { name = "ATLAS_ANCHOR_PUBLIC_KEYS", valueFrom = aws_secretsmanager_secret.anchor_public_keys.arn },
  ] : [])

  log = { for k in ["api", "web"] : k => {
    logDriver = "awslogs"
    options = {
      awslogs-group         = aws_cloudwatch_log_group.app[k].name
      awslogs-region        = var.region
      awslogs-stream-prefix = k
    }
  } }
}

resource "aws_ecs_task_definition" "app" {
  #checkov:skip=CKV_AWS_336:The web container is read-only; the API's is not yet (see the comment on it)
  family                   = var.project
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  volume {
    name = "anchors"
    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.anchors.id
      transit_encryption = "ENABLED"
      authorization_config {
        access_point_id = aws_efs_access_point.anchors.id
        iam             = "ENABLED"
      }
    }
  }

  container_definitions = jsonencode([
    {
      name      = "api"
      image     = var.api_image
      essential = true
      # Writable: the JVM and Tomcat write to /tmp. Making this read-only with a task-storage
      # volume on /tmp is a follow-up to confirm on a real deploy (volume ownership for uid 10001).
      readonlyRootFilesystem = false
      environment            = local.api_environment
      secrets                = local.api_secrets
      mountPoints            = [{ sourceVolume = "anchors", containerPath = "/anchors", readOnly = false }]
      logConfiguration       = local.log["api"]
      linuxParameters        = { initProcessEnabled = true }
    },
    {
      name                   = "web"
      image                  = var.web_image
      essential              = true
      readonlyRootFilesystem = true # Caddy is configured to write nothing (tested unprivileged, read-only)
      portMappings           = [{ containerPort = 8080, protocol = "tcp" }]
      environment            = [{ name = "ATLAS_API_UPSTREAM", value = "127.0.0.1:8081" }]
      dependsOn              = [{ containerName = "api", condition = "START" }]
      logConfiguration       = local.log["web"]
    }
  ])
}

resource "aws_ecs_service" "app" {
  #checkov:skip=CKV_AWS_333:Public IP instead of a NAT gateway (cost); inbound only from the load balancer SG
  name            = var.project
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  launch_type     = "FARGATE"
  # ONE task on purpose: rate limits are per instance and exactly one writer appends to the
  # anchor log (docs/security/hardening.md §5, §4). Scale out only after addressing both.
  desired_count                     = 1
  health_check_grace_period_seconds = 120
  enable_execute_command            = false
  propagate_tags                    = "SERVICE"

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.web.arn
    container_name   = "web"
    container_port   = 8080
  }

  # A new version that never gets healthy is rolled back automatically.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  # Stop the old task before starting the new one: never two anchor writers at once.
  deployment_maximum_percent         = 100
  deployment_minimum_healthy_percent = 0

  # Image rollouts are done by .github/workflows/deploy.yml (new task definition revision
  # with the new images); Terraform owns everything else.
  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.https]
}
