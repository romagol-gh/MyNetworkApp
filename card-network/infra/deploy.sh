#!/usr/bin/env bash
# deploy.sh — Build, push, and deploy card-network to AWS ECS Fargate.
#
# Usage:
#   ./infra/deploy.sh [image-tag]
#
# Prerequisites:
#   - AWS CLI configured (aws configure)
#   - Terraform installed (>= 1.7)
#   - Docker running
#   - S3 backend bucket created (see main.tf comment)
#   - Admin password seeded:
#       aws secretsmanager put-secret-value \
#         --secret-id card-network/admin-password \
#         --secret-string '{"ADMIN_PASSWORD":"<strong-pass>"}'

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INFRA_DIR="$REPO_ROOT/infra"
IMAGE_TAG="${1:-$(git -C "$REPO_ROOT" rev-parse --short HEAD)}"

echo "==> [1/5] Running Terraform..."
cd "$INFRA_DIR"
terraform init -upgrade
terraform apply -auto-approve

# Read outputs
ECR_URL=$(terraform output -raw ecr_repository_url)
AWS_REGION=$(terraform output -raw ecr_repository_url | cut -d. -f4)  # e.g. us-east-1
ECS_CLUSTER=$(terraform output -raw ecs_cluster_name)
ECS_SERVICE=$(terraform output -raw ecs_service_name)

echo "==> [2/5] Authenticating Docker with ECR..."
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$ECR_URL"

echo "==> [3/5] Building Docker image (tag: $IMAGE_TAG)..."
cd "$REPO_ROOT"
docker build -t "${ECR_URL}:${IMAGE_TAG}" -t "${ECR_URL}:latest" .

echo "==> [4/5] Pushing image to ECR..."
docker push "${ECR_URL}:${IMAGE_TAG}"
docker push "${ECR_URL}:latest"

echo "==> [5/5] Triggering ECS rolling deployment..."
aws ecs update-service \
  --cluster "$ECS_CLUSTER" \
  --service "$ECS_SERVICE" \
  --force-new-deployment \
  --region "$AWS_REGION" \
  --output json | jq -r '.service.deployments[] | "\(.status): \(.desiredCount) desired, \(.runningCount) running"'

echo ""
echo "Deployment triggered. Monitor at:"
echo "  https://console.aws.amazon.com/ecs/v2/clusters/$ECS_CLUSTER/services/$ECS_SERVICE/deployments"
echo ""
echo "Admin dashboard:"
echo "  http://$(cd "$INFRA_DIR" && terraform output -raw alb_dns_name)"
echo ""
echo "ISO 8583 TCP gateway:"
echo "  $(cd "$INFRA_DIR" && terraform output -raw nlb_dns_name):8583"
