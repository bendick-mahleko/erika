terraform {
  required_version = ">= 1.9"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.52"
    }
    # Only for Hetzner Object Storage (S3 API); no AWS account is involved.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # State in a dedicated bucket of the backup project (docs/ops/production.md):
  #   terraform init -backend-config=backend.hcl
  backend "s3" {}
}
