terraform {
  required_version = ">= 1.10"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  backend "s3" {
    bucket       = "ladder-tfstate-027401589864"
    key          = "prod/terraform.tfstate"
    region       = "ap-southeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "ap-southeast-2"
  default_tags {
    tags = { Project = "ladder" }
  }
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.7"

  name = "ladder"
  cidr = "10.0.0.0/16"

  # Two AZs: RDS subnet groups and ALBs both refuse a single-AZ setup.
  azs             = ["ap-southeast-2a", "ap-southeast-2b"]
  public_subnets  = ["10.0.0.0/24", "10.0.1.0/24"]   # ALB + Fargate tasks
  private_subnets = ["10.0.10.0/24", "10.0.11.0/24"] # RDS, Valkey, Amazon MQ

  # No NAT (~$40/mo): tasks sit in public subnets with a public IP to reach
  # ECR/CloudWatch, and their security group only admits the ALB. Data stores
  # never need the internet. ponytail: add a NAT + move tasks private if a
  # security review ever requires tasks without public IPs.
  enable_nat_gateway = false
}
