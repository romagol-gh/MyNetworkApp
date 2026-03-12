output "ecr_repository_url" {
  description = "ECR repository URL for docker push"
  value       = aws_ecr_repository.app.repository_url
}

output "alb_dns_name" {
  description = "ALB DNS name — admin dashboard at http://<this>/  (port 80 → 8080)"
  value       = aws_lb.app.dns_name
}

output "nlb_dns_name" {
  description = "NLB DNS name — ISO 8583 TCP gateway at <this>:8583"
  value       = aws_lb.gateway.dns_name
}

output "aurora_writer_endpoint" {
  description = "Aurora PostgreSQL writer endpoint"
  value       = aws_rds_cluster.main.endpoint
}

output "aurora_reader_endpoint" {
  description = "Aurora PostgreSQL reader endpoint (read-only replicas)"
  value       = aws_rds_cluster.main.reader_endpoint
}

output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  description = "ECS service name"
  value       = aws_ecs_service.app.name
}

output "cloudwatch_log_group" {
  description = "CloudWatch log group for ECS tasks"
  value       = aws_cloudwatch_log_group.app.name
}
