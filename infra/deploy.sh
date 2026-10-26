#!/usr/bin/env bash
# Build both images, push them, and tell ECS to restart on the new :latest.
# Run from anywhere; needs AWS_PROFILE=tf (or any logged-in profile) and Docker.
set -euo pipefail

REGION=ap-southeast-2
cd "$(dirname "$0")/.."

# The registry and cluster names come from Terraform, so nothing is hardcoded here.
REGISTRY=$(terraform -chdir=infra output -json ecr_repository_urls | python3 -c 'import json,sys; print(json.load(sys.stdin)["ladder-api"].split("/")[0])')
CLUSTER=$(terraform -chdir=infra output -raw ecs_cluster)

./gradlew bootJar   # not `build`: that also makes a -plain.jar and breaks the COPY

docker build -f Dockerfile.api    -t "$REGISTRY/ladder-api:latest"    .
docker build -f Dockerfile.worker -t "$REGISTRY/ladder-worker:latest" .

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"
docker push "$REGISTRY/ladder-api:latest"
docker push "$REGISTRY/ladder-worker:latest"

# ECS does not notice that :latest now points at a new image; force a new deployment.
for svc in api worker; do
  aws ecs update-service --cluster "$CLUSTER" --service "$svc" --force-new-deployment \
    --region "$REGION" --query 'service.serviceName' --output text
done
echo "Deploying. Watch: aws logs tail /ecs/ladder --follow --region $REGION"
