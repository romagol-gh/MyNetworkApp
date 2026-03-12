# ── Network Load Balancer (port 8583 → ISO 8583 TCP gateway) ─────────────────
#
# NLB uses flow-hash-based sticky sessions so the same acquirer/issuer TCP
# connection always routes to the same ECS task, preserving the in-memory
# SessionRegistry and PendingRequests correlation maps.

resource "aws_lb" "gateway" {
  name               = "${var.app_name}-nlb"
  internal           = false
  load_balancer_type = "network"
  subnets            = aws_subnet.public[*].id

  enable_deletion_protection       = true
  enable_cross_zone_load_balancing = true

  tags = { Name = "${var.app_name}-nlb" }
}

resource "aws_lb_target_group" "gateway" {
  name        = "${var.app_name}-gateway-tg"
  port        = var.gateway_port
  protocol    = "TCP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"   # required for Fargate

  # NLB sticky sessions: route the same client IP to the same target
  stickiness {
    type    = "source_ip"
    enabled = true
  }

  health_check {
    protocol            = "TCP"
    port                = var.gateway_port
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }

  tags = { Name = "${var.app_name}-gateway-tg" }
}

resource "aws_lb_listener" "gateway" {
  load_balancer_arn = aws_lb.gateway.arn
  port              = var.gateway_port
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
}
