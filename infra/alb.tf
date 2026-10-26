locals {
  domain = "ladder.nguyentiendat.dev"
}

# Step 1 of Phase 4: ask ACM for a certificate. It stays "pending" until we
# prove we own the domain by adding a DNS record -- at name.com, by hand,
# because the domain's DNS isn't in Route 53.
resource "aws_acm_certificate" "this" {
  domain_name       = local.domain
  validation_method = "DNS"

  # Lets a changed certificate be replaced without downtime once the load
  # balancer is using it.
  lifecycle {
    create_before_destroy = true
  }
}

# Step 2: blocks until ACM sees the CNAME we added at name.com and issues the
# certificate. No validation_record_fqdns: that is only for Route 53 records
# Terraform itself manages. Everything below that needs a certificate uses
# this resource's ARN, so it can't start before the certificate is issued.
resource "aws_acm_certificate_validation" "this" {
  certificate_arn = aws_acm_certificate.this.arn
}

# ---------------------------------------------------------------- security

resource "aws_security_group" "alb" {
  name        = "ladder-alb"
  description = "public load balancer"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "alb_public" {
  for_each = toset(["80", "443"]) # 80 only redirects to 443

  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = each.value
  to_port           = each.value
  cidr_ipv4         = "0.0.0.0/0"
}

# The load balancer may only talk to the tasks, and only on the app port.
resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# The one inbound rule on the app group: from the load balancer, nothing else.
resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# ---------------------------------------------------------------- load balancer

resource "aws_lb" "this" {
  name               = "ladder"
  load_balancer_type = "application"
  subnets            = module.vpc.public_subnets
  security_groups    = [aws_security_group.alb.id]
}

resource "aws_lb_target_group" "api" {
  name        = "ladder-api"
  vpc_id      = module.vpc.vpc_id
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip" # required for Fargate: tasks are IPs, not instances

  # Default is 300s, which makes every deploy wait 5 minutes for the old task.
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health" # open to anonymous callers in SecurityConfig
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.this.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}
