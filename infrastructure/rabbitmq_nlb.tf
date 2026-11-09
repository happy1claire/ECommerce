# Target Group for AMQP (port 5672)
resource "aws_lb_target_group" "rabbitmq_amqp" {
  name        = "rabbitmq-tg-amqp"
  port        = 5672
  protocol    = "TCP"
  vpc_id      = data.aws_vpc.default.id
  target_type = "ip"  # Use "ip" for Fargate, "instance" for EC2

  health_check {
    enabled             = true
    protocol            = "TCP"
    port                = 5672
    healthy_threshold   = 2
    unhealthy_threshold = 2
    interval            = 30
  }

  deregistration_delay = 30

  tags = {
    Name = "rabbitmq-amqp-target-group"
  }
}

# Target Group for Management UI (port 15672)
resource "aws_lb_target_group" "rabbitmq_management" {
  name        = "rabbitmq-tg-management"
  port        = 15672
  protocol    = "TCP"
  vpc_id      = data.aws_vpc.default.id
  target_type = "ip"  # Use "ip" for Fargate, "instance" for EC2

  health_check {
    enabled             = true
    protocol            = "TCP"
    port                = 15672
    healthy_threshold   = 2
    unhealthy_threshold = 2
    interval            = 30
  }

  deregistration_delay = 30

  tags = {
    Name = "rabbitmq-management-target-group"
  }
}

# Network Load Balancer
resource "aws_lb" "rabbitmq" {
  name               = "rabbitmq-nlb"
  internal           = true # internal-only: never expose DB replicas or the broker to the internet
  load_balancer_type = "network"
  security_groups    = [aws_security_group.internal_lb.id]
  subnets            = data.aws_subnets.default.ids

  enable_deletion_protection       = false  # Set to true in production
  enable_cross_zone_load_balancing = true

  tags = {
    Name = "rabbitmq-nlb"
  }
}

# Listener for AMQP (port 5672)
resource "aws_lb_listener" "rabbitmq_amqp" {
  load_balancer_arn = aws_lb.rabbitmq.arn
  port              = "5672"
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.rabbitmq_amqp.arn
  }

  tags = {
    Name = "rabbitmq-amqp-listener"
  }
}

# Listener for Management UI (port 15672)
resource "aws_lb_listener" "rabbitmq_management" {
  load_balancer_arn = aws_lb.rabbitmq.arn
  port              = "15672"
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.rabbitmq_management.arn
  }

  tags = {
    Name = "rabbitmq-management-listener"
  }
}

# Outputs
output "nlb_dns_name" {
  description = "DNS name of the Network Load Balancer"
  value       = aws_lb.rabbitmq.dns_name
}

output "rabbitmq_amqp_url" {
  description = "AMQP connection URL"
  value       = "amqp://${aws_lb.rabbitmq.dns_name}:5672"
}

output "rabbitmq_management_url" {
  description = "Management UI URL (internal NLB: reachable only from inside the VPC)"
  value       = "http://${aws_lb.rabbitmq.dns_name}:15672"
}