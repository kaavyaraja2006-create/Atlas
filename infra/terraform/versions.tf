terraform {
  # Works with Terraform >= 1.10 and OpenTofu >= 1.10 (validated with OpenTofu 1.10.7 and AWS provider 6.70.0).
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.70"
    }
  }

  # State in S3 with native locking. Configure at init:
  #   terraform init -backend-config=backend.hcl   (see backend.hcl.example)
  backend "s3" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
    }
  }
}
