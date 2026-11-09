# --------------------------------------------------------------------------
# 3. Target Groups
# --------------------------------------------------------------------------

resource "aws_lb_target_group" "shoppingcartdb_tg" {
  name        = "shoppingcartdb-tg"
  port        = 8080        # The port your container listens on
  protocol    = "HTTP"
  vpc_id      = data.aws_vpc.default.id
  target_type = "ip"        # Required for ECS Fargate (awsvpc)

  # ALB Health Check (Standard HTTP)
  health_check {
    enabled             = true
    path                = "/actuator/health"
    protocol            = "HTTP"
    port                = "traffic-port" # Best practice: uses the target's port (8080) automatically
    healthy_threshold   = 5
    unhealthy_threshold = 2
    timeout             = 5
    interval            = 20
    matcher             = "200"
  }
}

# --------------------------------------------------------------------------
# 4. The Application Load Balancer
# --------------------------------------------------------------------------

resource "aws_lb" "shoppingcartdb" {
  name               = "shoppingcartdb-alb"
  internal           = true # internal-only: never expose DB replicas or the broker to the internet
  load_balancer_type = "application"
  security_groups    = [aws_security_group.internal_lb.id]
  subnets            = data.aws_subnets.default.ids

  tags = {
    Name = "shoppingcartdb-alb"
  }
}

# --------------------------------------------------------------------------
# 5. Listeners and Rules
# --------------------------------------------------------------------------

resource "aws_lb_listener" "shoppingcartdb_http" {
  load_balancer_arn = aws_lb.shoppingcartdb.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.shoppingcartdb_tg.arn
  }
}

# --------------------------------------------------------------------------
# 6. Outputs
# --------------------------------------------------------------------------

output "shoppingcartdb_alb_dns_name" {
  description = "The DNS name of the ALB (your API endpoint)"
  value       = aws_lb.shoppingcartdb.dns_name
}
