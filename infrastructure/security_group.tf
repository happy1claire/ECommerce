# --------------------------------------------------------------------------
# Security Group for the public Load Balancer
# - Attached to: aws_lb.main (the public e-commerce ALB) only.
# - Role: Receives public internet traffic on port 80.
# - The DB ALBs and the RabbitMQ NLB use aws_security_group.internal_lb.
# --------------------------------------------------------------------------
resource "aws_security_group" "lb" {
  name        = "ecommerce-lb-sg"
  description = "Security group for all public-facing LBs (ALB & NLB)"
  vpc_id      = data.aws_vpc.default.id

  # --- Ingress (Inbound) ---

  # Port 80 - HTTP traffic from internet to ALB
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "HTTP from internet to ALB"
  }

  # --- Egress (Outbound) ---
  # LBs need to send traffic to the services on their respective ports.
  # We restrict this to ONLY the ecs_services security group.

  # Port 8080 - To Spring Boot services (Product, Cart, Payment)
  # egress {
  #   from_port       = 8080
  #   to_port         = 8080
  #   protocol        = "tcp"
  #   security_groups = [aws_security_group.ecs_services.id]
  #   description     = "ALB to Spring Boot tasks"
  # }

  # Port 5672 - To RabbitMQ AMQP task
  # egress {
  #   from_port       = 5672
  #   to_port         = 5672
  #   protocol        = "tcp"
  #   security_groups = [aws_security_group.ecs_services.id]
  #   description     = "NLB to RabbitMQ AMQP port"
  # }

  # Port 15672 - To RabbitMQ Management task
  # egress {
  #   from_port       = 15672
  #   to_port         = 15672
  #   protocol        = "tcp"
  #   security_groups = [aws_security_group.ecs_services.id]
  #   description     = "NLB to RabbitMQ Management port"
  # }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Allow all outbound traffic"
  }

  tags = {
    Name = "ecommerce-lb-sg"
  }
}

# --------------------------------------------------------------------------
# Security Group for internal-only Load Balancers
# - Attached to: the three DB ALBs (productdb, customerdb, shoppingcartdb)
#   and aws_lb.rabbitmq (NLB). All of them are internal = true.
# - Role: Accept traffic only from inside the VPC (the ECS services).
#   The DB control plane (/config/*, /propagate) and the RabbitMQ broker
#   must never be reachable from the internet.
# --------------------------------------------------------------------------
resource "aws_security_group" "internal_lb" {
  name        = "ecommerce-internal-lb-sg"
  description = "Internal-only LBs (DB ALBs and RabbitMQ NLB), VPC traffic only"
  vpc_id      = data.aws_vpc.default.id

  # Port 80 - HTTP to the DB ALBs from services inside the VPC
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.default.cidr_block]
    description = "HTTP to DB ALBs from within VPC"
  }

  # Port 5672 - RabbitMQ AMQP from services inside the VPC
  ingress {
    from_port   = 5672
    to_port     = 5672
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.default.cidr_block]
    description = "RabbitMQ AMQP from within VPC to NLB"
  }

  # Port 15672 - RabbitMQ Management UI, only from inside the VPC
  # (e.g. via SSM/ECS Exec port forwarding or a bastion host).
  ingress {
    from_port   = 15672
    to_port     = 15672
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.default.cidr_block]
    description = "RabbitMQ Management from within VPC to NLB"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Allow all outbound traffic"
  }

  tags = {
    Name = "ecommerce-internal-lb-sg"
  }
}

# --------------------------------------------------------------------------
# Security Group for ECS Services (Fargate Tasks)
# - Attached to: All aws_ecs_service resources.
# - Role: Receives traffic ONLY from the load balancers.
# --------------------------------------------------------------------------
resource "aws_security_group" "ecs_services" {
  name        = "ecommerce-ecs-services-sg"
  description = "Security group for all ECS services (Cart, Rabbit, etc)"
  vpc_id      = data.aws_vpc.default.id

  # --- Ingress (Inbound) ---
  # Allow traffic ONLY from the Load Balancer Security Group

  # Port 8080 - From the ALBs (for Spring Boot apps) and from other tasks
  # in this group. self = true is required because DB replicas replicate
  # to each other directly on <private-ip>:8080 (/propagate, /local_read).
  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.lb.id, aws_security_group.internal_lb.id] # ONLY allow from our LBs
    self            = true
    description     = "Spring Boot tasks from ALBs and from peer tasks"
  }

  # Port 5672 - From NLB (for RabbitMQ AMQP)
  ingress {
    from_port       = 5672
    to_port         = 5672
    protocol        = "tcp"
    security_groups = [aws_security_group.internal_lb.id] # ONLY allow from our internal NLB
    description     = "RabbitMQ AMQP from NLB"
  }

  # Port 15672 - From NLB (for RabbitMQ Management)
  ingress {
    from_port       = 15672
    to_port         = 15672
    protocol        = "tcp"
    security_groups = [aws_security_group.internal_lb.id] # ONLY allow from our internal NLB
    description     = "RabbitMQ Management from NLB"
  }

  # --- Egress (Outbound) ---
  
  # Allow all outbound traffic. This is required for Fargate tasks to:
  # 1. Pull container images from ECR.
  # 2. Communicate with AWS services (e.g., CloudWatch Logs).
  # 3. Resolve and connect to the Load Balancer DNS names.
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Allow all outbound traffic"
  }

  tags = {
    Name = "ecommerce-ecs-services-sg"
  }
}