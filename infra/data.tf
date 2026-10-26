data "aws_caller_identity" "me" {}

# ---------------------------------------------------------------- security groups

# The ECS tasks (Phase 5). No inbound rules here: the load balancer rule is
# added in Phase 4, so nothing can reach a task until we say so.
resource "aws_security_group" "app" {
  name        = "ladder-app"
  description = "ladder api and worker tasks"
  vpc_id      = module.vpc.vpc_id
}

# Tasks must reach ECR, CloudWatch, S3 and the data stores.
resource "aws_vpc_security_group_egress_rule" "app_all" {
  security_group_id = aws_security_group.app.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# One group for all three data stores. Each only admits its own port, and
# only from the app group -- not from the VPC, not from the internet.
resource "aws_security_group" "data" {
  name        = "ladder-data"
  description = "postgres, valkey and rabbitmq"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "data_from_app" {
  for_each = {
    postgres = 5432
    valkey   = 6379
    rabbitmq = 5671 # amqps; Amazon MQ refuses plain 5672
  }

  security_group_id            = aws_security_group.data.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = each.value
  to_port                      = each.value
}

# ---------------------------------------------------------------- passwords

# Why not RDS's managed master password: AWS rotates it every 7 days and a
# running connection pool keeps the old one until the app restarts.
# "_-" only: Postgres and RabbitMQ both reject some of the default symbols.
resource "random_password" "db" {
  length           = 32
  special          = true
  override_special = "_-"
}

resource "random_password" "mq" {
  length           = 32
  special          = true
  override_special = "_-"
}

# Parameter Store, not Secrets Manager: standard parameters are free.
# ECS reads these into the container at start (Phase 5).
resource "aws_ssm_parameter" "db_password" {
  name  = "/ladder/db-password"
  type  = "SecureString"
  value = random_password.db.result
}

resource "aws_ssm_parameter" "mq_password" {
  name  = "/ladder/mq-password"
  type  = "SecureString"
  value = random_password.mq.result
}

# ---------------------------------------------------------------- postgres

resource "aws_db_subnet_group" "this" {
  name       = "ladder"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_db_instance" "this" {
  identifier = "ladder"

  engine         = "postgres"
  engine_version = "18" # major only: RDS applies minors itself; pinning one drifts
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "ladder"
  username = "ladder"
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.data.id]
  publicly_accessible    = false

  auto_minor_version_upgrade = true
  backup_retention_period    = 7

  # ponytail: no snapshot on destroy while there is only test data.
  # Set false (and add final_snapshot_identifier) before real users arrive.
  skip_final_snapshot = true
}

# ---------------------------------------------------------------- valkey

resource "aws_elasticache_subnet_group" "this" {
  name       = "ladder"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "ladder"
  description          = "ladder view counters"

  engine         = "valkey"
  engine_version = "8.2"
  node_type      = "cache.t4g.micro"

  num_cache_clusters         = 1
  automatic_failover_enabled = false

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.data.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true # the app sets SPRING_DATA_REDIS_SSL_ENABLED=true
}

# ---------------------------------------------------------------- rabbitmq

resource "aws_mq_broker" "this" {
  broker_name = "ladder"

  engine_type        = "RabbitMQ"
  engine_version     = "4.3"
  host_instance_type = "mq.m7g.medium" # smallest offered in ap-southeast-2
  deployment_mode    = "SINGLE_INSTANCE"

  auto_minor_version_upgrade = true
  publicly_accessible        = false
  subnet_ids                 = [module.vpc.private_subnets[0]] # single instance = one subnet
  security_groups            = [aws_security_group.data.id]

  user {
    username = "ladder"
    password = random_password.mq.result
  }
}

# ---------------------------------------------------------------- video bucket

resource "aws_s3_bucket" "videos" {
  bucket = "ladder-videos-${data.aws_caller_identity.me.account_id}"
}

# Videos are served with presigned URLs (later CloudFront), never publicly.
# The app sets the bucket's CORS rules at startup, so none here: two owners
# of one setting would overwrite each other.
resource "aws_s3_bucket_public_access_block" "videos" {
  bucket = aws_s3_bucket.videos.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
