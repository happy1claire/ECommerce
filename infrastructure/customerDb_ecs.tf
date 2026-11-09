# CloudWatch Log Group for customerdb
resource "aws_cloudwatch_log_group" "customerdb" {
  name              = "/ecs/customerdb"
  retention_in_days = 7

  tags = {
    Name = "customerdb-logs"
  }
}

# ECS Task Definition for customerdb
resource "aws_ecs_task_definition" "customerdb" {
  family                   = "customerdb"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024" # 1 vCPU
  memory                   = "2048" # 2 GB
  execution_role_arn       = data.aws_iam_role.lab_role.arn
  task_role_arn            = data.aws_iam_role.lab_role.arn

  container_definitions = jsonencode([{
    name      = "customerdb"
    image     = "${aws_ecr_repository.customerdb.repository_url}:latest"
    essential = true

    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]

    # Used by entrypoint.sh to discover the other replicas of this service.
    # (Literal service name: referencing aws_ecs_service here would be a cycle.)
    environment = [
      {
        name  = "ECS_CLUSTER_NAME"
        value = aws_ecs_cluster.main.name
      },
      {
        name  = "ECS_SERVICE_NAME"
        value = "customerdb-service"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.customerdb.name
        "awslogs-region"        = "us-east-1"
        "awslogs-stream-prefix" = "customerdb"
      }
    }
  }])

  tags = {
    Name = "customerdb-task-definition"
  }
}

# ECS Service for customerdb
resource "aws_ecs_service" "customerdb" {
  name            = "customerdb-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.customerdb.arn
  desired_count   = 5
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.ecs_services.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.customerdb_tg.arn
    container_name   = "customerdb"
    container_port   = 8080
  }

  depends_on = [
    aws_lb_listener.customerdb_http
  ]

  tags = {
    Name = "customerdb-ecs-service"
  }
}