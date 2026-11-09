# CloudWatch Log Group for shoppingcartdb
resource "aws_cloudwatch_log_group" "shoppingcartdb" {
  name              = "/ecs/shoppingcartdb"
  retention_in_days = 7

  tags = {
    Name = "shoppingcartdb-logs"
  }
}

# ECS Task Definition for shoppingcartdb
resource "aws_ecs_task_definition" "shoppingcartdb" {
  family                   = "shoppingcartdb"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024" # 1 vCPU
  memory                   = "2048" # 2 GB
  execution_role_arn       = data.aws_iam_role.lab_role.arn
  task_role_arn            = data.aws_iam_role.lab_role.arn

  container_definitions = jsonencode([{
    name      = "shoppingcartdb"
    image     = "${aws_ecr_repository.shoppingcartdb.repository_url}:latest"
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
        value = "shoppingcartdb-service"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.shoppingcartdb.name
        "awslogs-region"        = "us-east-1"
        "awslogs-stream-prefix" = "shoppingcartdb"
      }
    }
  }])

  tags = {
    Name = "shoppingcartdb-task-definition"
  }
}

# ECS Service for shoppingcartdb
resource "aws_ecs_service" "shoppingcartdb" {
  name            = "shoppingcartdb-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.shoppingcartdb.arn
  desired_count   = 5
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.ecs_services.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.shoppingcartdb_tg.arn
    container_name   = "shoppingcartdb"
    container_port   = 8080
  }

  depends_on = [
    aws_lb_listener.shoppingcartdb_http
  ]

  tags = {
    Name = "shoppingcartdb-ecs-service"
  }
}