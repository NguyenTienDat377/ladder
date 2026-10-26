output "vpc_id" {
  value = module.vpc.vpc_id
}

output "public_subnets" {
  value = module.vpc.public_subnets
}

output "private_subnets" {
  value = module.vpc.private_subnets
}

output "db_endpoint" {
  value = aws_db_instance.this.address
}

output "valkey_endpoint" {
  value = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "rabbitmq_endpoint" {
  value = aws_mq_broker.this.instances[0].endpoints[0] # amqps://...:5671
}

output "video_bucket" {
  value = aws_s3_bucket.videos.bucket
}

# The record to create at name.com (type, name, value).
output "certificate_validation_records" {
  value = {
    for o in aws_acm_certificate.this.domain_validation_options :
    o.domain_name => { type = o.resource_record_type, name = o.resource_record_name, value = o.resource_record_value }
  }
}

output "ecr_repository_urls" {
  value = { for name, repo in aws_ecr_repository.this : name => repo.repository_url }
}

# What the `ladder` CNAME at name.com points to (Phase 6).
output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

# For deploy.sh.
output "ecs_cluster" {
  value = aws_ecs_cluster.this.name
}
