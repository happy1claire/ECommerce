# CloudWatch Log Group for productDB
resource "aws_cloudwatch_log_group" "productdb" {
  name              = "/ecs/productdb"
  retention_in_days = 7

  tags = {
    Name = "productdb-logs"
  }
}

# ECS Task Definition for productDB
resource "aws_ecs_task_definition" "productdb" {
  family                   = "productdb"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024" # 1 vCPU
  memory                   = "2048" # 2 GB
  execution_role_arn       = data.aws_iam_role.lab_role.arn
  task_role_arn            = data.aws_iam_role.lab_role.arn

  container_definitions = jsonencode([{
    name      = "productdb"
    image     = "${aws_ecr_repository.productdb.repository_url}:latest"
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
        value = "productdb-service"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.productdb.name
        "awslogs-region"        = "us-east-1"
        "awslogs-stream-prefix" = "productdb"
      }
    }
  }])

  tags = {
    Name = "productdb-task-definition"
  }
}

# ECS Service for productDB
resource "aws_ecs_service" "productdb" {
  name            = "productdb-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.productdb.arn
  desired_count   = 5
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.ecs_services.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.productdb_tg.arn
    container_name   = "productdb"
    container_port   = 8080
  }

  depends_on = [
    aws_lb_listener.productdb_http
  ]

  tags = {
    Name = "productdb-ecs-service"
  }
}