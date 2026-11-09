# CloudWatch Log Group for Product
resource "aws_cloudwatch_log_group" "product" {
  name              = "/ecs/product"
  retention_in_days = 7

  tags = {
    Name = "product-logs"
  }
}

# ECS Task Definition for Product
resource "aws_ecs_task_definition" "product" {
  family                   = "product"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"  # 0.5 vCPU
  memory                   = "1024" # 1 GB
  execution_role_arn       = data.aws_iam_role.lab_role.arn
  task_role_arn            = data.aws_iam_role.lab_role.arn

  container_definitions = jsonencode([{
    name      = "product"
    image     = "${aws_ecr_repository.product.repository_url}:latest"
    essential = true

    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]

    environment = [
      {
        name  = "SERVER_PORT"
        value = "8080"
      },
      {
        # Without this, productdb.base-url falls back to http://localhost:8080,
        # which is product-service itself inside this task (infinite self-call).
        name  = "PRODUCTDB_BASE_URL"
        value = "http://${aws_lb.productdb.dns_name}"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.product.name
        "awslogs-region"        = "us-east-1"
        "awslogs-stream-prefix" = "product"
      }
    }
  }])

  tags = {
    Name = "product-task-definition"
  }
}

# ECS Service for product
resource "aws_ecs_service" "product" {
  name            = "product-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.product.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.ecs_services.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.product_tg.arn
    container_name   = "product"
    container_port   = 8080
  }

  depends_on = [
    aws_lb_listener.http
  ]

  tags = {
    Name = "product-ecs-service"
  }
  lifecycle {
    ignore_changes = [desired_count]
  }
}

resource "aws_appautoscaling_target" "product_target" {
  max_capacity       = 3    # Maximum number of tasks to run
  min_capacity       = 1    # Minimum number of tasks to run
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.product.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "product_cpu" {
  name               = "product-cpu-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.product_target.resource_id
  scalable_dimension = aws_appautoscaling_target.product_target.scalable_dimension
  service_namespace  = aws_appautoscaling_target.product_target.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    
    # Keep average CPU at 70%. If it goes higher, scale up. Lower, scale down.
    target_value       = 70.0 
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}

resource "aws_appautoscaling_policy" "product_memory" {
  name               = "product-memory-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.product_target.resource_id
  scalable_dimension = aws_appautoscaling_target.product_target.scalable_dimension
  service_namespace  = aws_appautoscaling_target.product_target.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageMemoryUtilization"
    }
    
    target_value       = 80.0
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}