# Hetzner Object Storage buckets (S3 API; ADR 0013, 0022, 0023, 0025, 0028).
# Two projects with separate keys:
#   primary (erika-prod, fsn1): what the app uses; Object Lock COMPLIANCE on originals
#   backup  (erika-backup, nbg1): pgBackRest repository and replicas; keys only on the db server
#
# Note: newer AWS providers send CRC checksums on uploads by default; if Hetzner rejects a
# request, set AWS_REQUEST_CHECKSUM_CALCULATION=when_required before `terraform apply`.

provider "aws" {
  alias                       = "primary"
  region                      = var.s3_primary_location
  access_key                  = var.s3_primary_access_key
  secret_key                  = var.s3_primary_secret_key
  skip_credentials_validation = true
  skip_region_validation      = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
  endpoints {
    s3 = "https://${var.s3_primary_location}.your-objectstorage.com"
  }
}

provider "aws" {
  alias                       = "backup"
  region                      = var.s3_backup_location
  access_key                  = var.s3_backup_access_key
  secret_key                  = var.s3_backup_secret_key
  skip_credentials_validation = true
  skip_region_validation      = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
  endpoints {
    s3 = "https://${var.s3_backup_location}.your-objectstorage.com"
  }
}

# --- primary --------------------------------------------------------------------------

# Originals: write-once. Object Lock can only be enabled at creation (ADR 0013); the app
# sets COMPLIANCE retention per object up to its retention date (ADR 0022).
resource "aws_s3_bucket" "originals" {
  provider            = aws.primary
  bucket              = "${var.bucket_prefix}-originals"
  object_lock_enabled = true

  lifecycle {
    prevent_destroy = true
  }
}

# Audit chain heads, outside the database (ADR 0019).
resource "aws_s3_bucket" "audit_heads" {
  provider            = aws.primary
  bucket              = "${var.bucket_prefix}-audit-heads"
  object_lock_enabled = true

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_object_lock_configuration" "audit_heads" {
  provider = aws.primary
  bucket   = aws_s3_bucket.audit_heads.id
  rule {
    default_retention {
      mode = "COMPLIANCE"
      days = 400
    }
  }
}

# Quarantine for uploads before the virus scan (ADR 0023).
resource "aws_s3_bucket" "uploads" {
  provider = aws.primary
  bucket   = "${var.bucket_prefix}-uploads"
}

resource "aws_s3_bucket_lifecycle_configuration" "uploads" {
  provider = aws.primary
  bucket   = aws_s3_bucket.uploads.id
  rule {
    id     = "expire-unprocessed-uploads"
    status = "Enabled"
    filter {}
    expiration {
      days = 1
    }
  }
}

resource "aws_s3_bucket_cors_configuration" "uploads" {
  provider = aws.primary
  bucket   = aws_s3_bucket.uploads.id
  cors_rule {
    allowed_origins = ["https://${var.domain}"]
    allowed_methods = ["PUT"]
    allowed_headers = ["*"]
    max_age_seconds = 3600
  }
}

# PDF view copies and decoded attachments; rebuilt from originals when needed (ADR 0025).
resource "aws_s3_bucket" "derived" {
  provider = aws.primary
  bucket   = "${var.bucket_prefix}-derived"
}

# Export ZIPs (ADR 0032): the app deletes them after 7 days; the lifecycle rule removes
# anything left over (e.g. after a failed clean-up).
resource "aws_s3_bucket" "exports" {
  provider = aws.primary
  bucket   = "${var.bucket_prefix}-exports"
}

resource "aws_s3_bucket_lifecycle_configuration" "exports" {
  provider = aws.primary
  bucket   = aws_s3_bucket.exports.id
  rule {
    id     = "expire-exports"
    status = "Enabled"
    filter {}
    expiration {
      days = 8
    }
  }
}

# --- backup (second location) ---------------------------------------------------------

# pgBackRest repository: encrypted client-side; pgBackRest itself expires old backups, so
# no Object Lock here. Its keys exist only on the database server.
resource "aws_s3_bucket" "pgbackrest" {
  provider = aws.backup
  bucket   = "${var.bucket_prefix}-pgbackrest"

  lifecycle {
    prevent_destroy = true
  }
}

# Replicas of the originals and audit heads (rclone copy, nightly). Default COMPLIANCE
# retention: copies can't be deleted for that long, even with the backup keys.
resource "aws_s3_bucket" "originals_replica" {
  provider            = aws.backup
  bucket              = "${var.bucket_prefix}-originals-replica"
  object_lock_enabled = true

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket" "audit_heads_replica" {
  provider            = aws.backup
  bucket              = "${var.bucket_prefix}-audit-heads-replica"
  object_lock_enabled = true

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_object_lock_configuration" "originals_replica" {
  provider = aws.backup
  bucket   = aws_s3_bucket.originals_replica.id
  rule {
    default_retention {
      mode = "COMPLIANCE"
      days = var.replica_retention_days
    }
  }
}

resource "aws_s3_bucket_object_lock_configuration" "audit_heads_replica" {
  provider = aws.backup
  bucket   = aws_s3_bucket.audit_heads_replica.id
  rule {
    default_retention {
      mode = "COMPLIANCE"
      days = var.replica_retention_days
    }
  }
}
