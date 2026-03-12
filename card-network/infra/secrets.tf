# ── Secrets Manager ───────────────────────────────────────────────────────────
#
# Aurora manages its own master password secret automatically (manage_master_user_password=true).
# We only need to store ADMIN_PASSWORD for the Spring Boot admin dashboard.

resource "aws_secretsmanager_secret" "admin_password" {
  name                    = "${var.app_name}/admin-password"
  description             = "Spring Boot admin dashboard password"
  recovery_window_in_days = 7

  tags = { Name = "${var.app_name}-admin-password" }
}

# Set the initial value with: aws secretsmanager put-secret-value \
#   --secret-id card-network/admin-password --secret-string '{"ADMIN_PASSWORD":"<strong-pass>"}'
