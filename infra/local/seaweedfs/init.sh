#!/bin/sh
# Creates the originals bucket as in production: Object Lock enabled (implies versioning).
# Runs with the storage admin key; the app's own key can't create or delete buckets.
set -eu
if aws --endpoint-url "$S3_ENDPOINT" s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "Bucket $BUCKET exists"
else
  aws --endpoint-url "$S3_ENDPOINT" s3api create-bucket --bucket "$BUCKET" --object-lock-enabled-for-bucket
  echo "Bucket $BUCKET created with Object Lock"
fi
