# ── ECS Cluster ───────────────────────────────────────────────────────────────

resource "aws_ecs_cluster" "main" {
  name = "${var.app_name}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = { Name = "${var.app_name}-cluster" }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    base              = 1
    weight            = 100
    capacity_provider = "FARGATE"
  }
}

# ── ECS Task Definition ───────────────────────────────────────────────────────

locals {
  # Aurora connection string (writer endpoint)
  db_url = "jdbc:postgresql://${aws_rds_cluster.main.endpoint}:5432/${var.db_name}"
}

resource "aws_ecs_task_definition" "app" {
  family                   = var.app_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.ecs_task_cpu
  memory                   = var.ecs_task_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name      = var.app_name
      image     = "${aws_ecr_repository.app.repository_url}:latest"
      essential = true

      portMappings = [
        { containerPort = var.app_port,     protocol = "tcp" },
        { containerPort = var.gateway_port, protocol = "tcp" }
      ]

      environment = [
        { name = "SPRING_DATASOURCE_URL",                value = local.db_url },
        { name = "SPRING_DATASOURCE_USERNAME",           value = var.db_username },
        { name = "SPRING_JPA_HIBERNATE_DDL_AUTO",        value = "validate" },
        { name = "SERVER_PORT",                          value = tostring(var.app_port) },
        { name = "GATEWAY_PORT",                         value = tostring(var.gateway_port) },
        { name = "JAVA_OPTS",                            value = "-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC" }
      ]

      secrets = [
        {
          name      = "SPRING_DATASOURCE_PASSWORD"
          valueFrom = "${aws_rds_cluster.main.master_user_secret[0].secret_arn}:password::"
        },
        {
          name      = "ADMIN_PASSWORD"
          valueFrom = "${aws_secretsmanager_secret.admin_password.arn}:ADMIN_PASSWORD::"
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }

      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:${var.app_port}/actuator/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }
    }
  ])

  tags = { Name = "${var.app_name}-task" }
}

# ── ECS Service ───────────────────────────────────────────────────────────────

resource "aws_ecs_service" "app" {
  name            = "${var.app_name}-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.ecs_desired_count
  launch_type     = "FARGATE"

  # Spread tasks across AZs for active-active HA
  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = false
  }

  # Register tasks with both ALB (HTTP) and NLB (TCP) target groups
  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = var.app_name
    container_port   = var.app_port
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.gateway.arn
    container_name   = var.app_name
    container_port   = var.gateway_port
  }

  depends_on = [
    aws_lb_listener.http,
    aws_lb_listener.gateway,
    aws_iam_role_policy_attachment.ecs_execution_managed
  ]

  tags = { Name = "${var.app_name}-service" }
}

# ── Auto-scaling ──────────────────────────────────────────────────────────────

resource "aws_appautoscaling_target" "ecs" {
  max_capacity       = 6
  min_capacity       = 2
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.app.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "ecs_cpu" {
  name               = "${var.app_name}-cpu-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value = 70.0

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}
