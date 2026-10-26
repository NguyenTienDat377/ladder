data "aws_region" "current" {}

# ---------------------------------------------------------------- IAM

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Two roles on purpose. The EXECUTION role is what ECS itself uses to start a
# container (pull the image, write logs, fetch the secrets). The TASK role is
# what our Java code runs as. Keeping them apart means the app can never read
# secrets it was not given.
resource "aws_iam_role" "execution" {
  name               = "ladder-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "read-ladder-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameters"]
      Resource = [aws_ssm_parameter.db_password.arn, aws_ssm_parameter.mq_password.arn]
    }]
  })
}

# One task role shared by API and worker. ponytail: split it if the API ever
# should not be able to delete objects.
resource "aws_iam_role" "task" {
  name               = "ladder-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "task_s3" {
  name = "videos-bucket"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.videos.arn}/*"
      },
      {
        # ListBucket: the startup bucketExists() check. PutBucketCORS: the app
        # sets the bucket's CORS rules itself at startup.
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:PutBucketCORS"]
        Resource = aws_s3_bucket.videos.arn
      }
    ]
  })
}

# ---------------------------------------------------------------- cluster + logs

resource "aws_ecs_cluster" "this" {
  name = "ladder"
}

# The default is to keep logs forever, and pay for them forever.
resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/ladder"
  retention_in_days = 14
}

# ---------------------------------------------------------------- task definitions

locals {
  # Spring maps env vars to properties by deleting the dashes, not by turning
  # them into underscores: ladder.storage.access-key is LADDER_STORAGE_ACCESSKEY.
  app_env = {
    SPRING_DATASOURCE_URL      = "jdbc:postgresql://${aws_db_instance.this.address}:5432/ladder"
    SPRING_DATASOURCE_USERNAME = "ladder"

    SPRING_RABBITMQ_ADDRESSES = aws_mq_broker.this.instances[0].endpoints[0] # amqps://...:5671
    SPRING_RABBITMQ_USERNAME  = "ladder"

    SPRING_DATA_REDIS_HOST        = aws_elasticache_replication_group.this.primary_endpoint_address
    SPRING_DATA_REDIS_SSL_ENABLED = "true"

    LADDER_STORAGE_ENDPOINT   = "https://s3.${data.aws_region.current.region}.amazonaws.com"
    LADDER_STORAGE_BUCKET     = aws_s3_bucket.videos.bucket
    LADDER_STORAGE_CORSORIGIN = "https://${local.domain}"
    # Empty = "no static key": StorageConfig then uses the task role.
    LADDER_STORAGE_ACCESSKEY = ""

    # TLS ends at the load balancer; without this Spring sees plain HTTP and
    # drops the Secure flag from the session and CSRF cookies.
    SERVER_FORWARDHEADERSSTRATEGY = "native"
    # The JVM uses only 25% of container memory by default.
    JAVA_TOOL_OPTIONS = "-XX:MaxRAMPercentage=75"
  }

  app_secrets = [
    { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = aws_ssm_parameter.db_password.arn },
    { name = "SPRING_RABBITMQ_PASSWORD", valueFrom = aws_ssm_parameter.mq_password.arn },
  ]

  # Same jar in both images; this is the one setting that makes the API stop
  # consuming transcode jobs (it still publishes them).
  api_env = merge(local.app_env, { SPRING_RABBITMQ_LISTENER_SIMPLE_AUTOSTARTUP = "false" })
}

resource "aws_ecs_task_definition" "api" {
  family                   = "ladder-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  # ARM64: our images are built on an ARM Mac, and Fargate ARM is ~20% cheaper.
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name         = "api"
    image        = "${aws_ecr_repository.this["ladder-api"].repository_url}:latest"
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment  = [for k, v in local.api_env : { name = k, value = v }]
    secrets      = local.app_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "api"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "ladder-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "2048"
  memory                   = "4096"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name        = "worker"
    image       = "${aws_ecr_repository.this["ladder-worker"].repository_url}:latest"
    essential   = true
    environment = [for k, v in local.app_env : { name = k, value = v }]
    secrets     = local.app_secrets
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "worker"
      }
    }
  }])
}

# ---------------------------------------------------------------- services

resource "aws_ecs_service" "api" {
  name            = "api"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.api.arn
  launch_type     = "FARGATE"

  # 1, not 2: logins live in each task's memory until Spring Session + Redis.
  desired_count = 1

  # Spring + Flyway take a while on 0.5 vCPU; without a grace period the load
  # balancer's health check kills the task before it has finished starting.
  health_check_grace_period_seconds = 180

  # A deploy whose new tasks never turn healthy rolls back by itself.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = module.vpc.public_subnets
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true # no NAT: this is how the task reaches ECR and logs
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  # ECS refuses to attach a target group that no listener points to yet.
  depends_on = [aws_lb_listener.https]
}

resource "aws_ecs_service" "worker" {
  name            = "worker"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.worker.arn
  launch_type     = "FARGATE"

  # Set to 0 between demos to stop paying for the biggest compute item.
  desired_count = 1

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = module.vpc.public_subnets
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true
  }
}
