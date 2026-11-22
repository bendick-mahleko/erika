#!/bin/sh
# Creates the buckets as in production: Object Lock enabled (implies versioning).
# $BUCKET holds the originals; $BACKUP_BUCKET the backup copies and audit head hashes.
# Runs with the storage admin key; the app's own key can't create or delete buckets.
set -eu
for b in "$BUCKET" "${BACKUP_BUCKET:-erika-backups}"; do
  if aws --endpoint-url "$S3_ENDPOINT" s3api head-bucket --bucket "$b" 2>/dev/null; then
    echo "Bucket $b exists"
  else
    aws --endpoint-url "$S3_ENDPOINT" s3api create-bucket --bucket "$b" --object-lock-enabled-for-bucket
    echo "Bucket $b created with Object Lock"
  fi
done
