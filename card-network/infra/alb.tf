# ── Application Load Balancer (port 8080 → HTTP dashboard + REST API) ─────────

resource "aws_lb" "app" {
  name               = "${var.app_name}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  enable_deletion_protection = true

  tags = { Name = "${var.app_name}-alb" }
}

resource "aws_lb_target_group" "app" {
  name        = "${var.app_name}-app-tg"
  port        = var.app_port
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"   # required for Fargate

  health_check {
    path                = "/actuator/health"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  tags = { Name = "${var.app_name}-app-tg" }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  # Redirect HTTP → HTTPS in production; for now forward directly
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}
