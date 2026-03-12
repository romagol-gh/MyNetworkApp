variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "us-east-1"
}

variable "app_name" {
  description = "Application name prefix for all resources"
  type        = string
  default     = "card-network"
}

variable "environment" {
  description = "Deployment environment (prod, staging)"
  type        = string
  default     = "prod"
}

variable "db_name" {
  description = "PostgreSQL database name"
  type        = string
  default     = "cardnetwork"
}

variable "db_username" {
  description = "PostgreSQL master username"
  type        = string
  default     = "card"
}

variable "db_instance_class" {
  description = "Aurora PostgreSQL writer instance class"
  type        = string
  default     = "db.t4g.medium"
}

variable "ecs_task_cpu" {
  description = "CPU units for each ECS task (1024 = 1 vCPU)"
  type        = number
  default     = 1024
}

variable "ecs_task_memory" {
  description = "Memory (MiB) for each ECS task"
  type        = number
  default     = 2048
}

variable "ecs_desired_count" {
  description = "Number of ECS tasks to run (across AZs)"
  type        = number
  default     = 2
}

variable "gateway_port" {
  description = "ISO 8583 TCP gateway port"
  type        = number
  default     = 8583
}

variable "app_port" {
  description = "HTTP admin dashboard / REST API port"
  type        = number
  default     = 8080
}
