#!/bin/sh
# Creates the buckets as in production. Runs with the storage admin key; the app's own key
# can't create or delete buckets.
#   $BUCKET          originals: Object Lock enabled (implies versioning), ADR 0013/0022
#   $BACKUP_BUCKET   backup copies and audit head hashes: Object Lock enabled
#   $DERIVED_BUCKET  PDF view copies and decoded attachments: no lock, rebuilt from originals
#   $UPLOADS_BUCKET  quarantine for uploads before the virus scan: no lock, objects expire
#                    after a day, CORS for the browser's pre-signed PUT (ADR 0023)
set -eu
for b in "$BUCKET" "${BACKUP_BUCKET:-erika-backups}"; do
  if aws --endpoint-url "$S3_ENDPOINT" s3api head-bucket --bucket "$b" 2>/dev/null; then
    echo "Bucket $b exists"
  else
    aws --endpoint-url "$S3_ENDPOINT" s3api create-bucket --bucket "$b" --object-lock-enabled-for-bucket
    echo "Bucket $b created with Object Lock"
  fi
done

DERIVED="${DERIVED_BUCKET:-erika-derived}"
if aws --endpoint-url "$S3_ENDPOINT" s3api head-bucket --bucket "$DERIVED" 2>/dev/null; then
  echo "Bucket $DERIVED exists"
else
  aws --endpoint-url "$S3_ENDPOINT" s3api create-bucket --bucket "$DERIVED"
  echo "Bucket $DERIVED created"
fi

UPLOADS="${UPLOADS_BUCKET:-erika-uploads}"
if aws --endpoint-url "$S3_ENDPOINT" s3api head-bucket --bucket "$UPLOADS" 2>/dev/null; then
  echo "Bucket $UPLOADS exists"
else
  aws --endpoint-url "$S3_ENDPOINT" s3api create-bucket --bucket "$UPLOADS"
  echo "Bucket $UPLOADS created"
fi
cat > /tmp/cors.json <<EOF
{"CORSRules":[{"AllowedOrigins":["${WEB_ORIGIN:-http://localhost:3000}"],"AllowedMethods":["PUT"],"AllowedHeaders":["*"],"MaxAgeSeconds":3600}]}
EOF
aws --endpoint-url "$S3_ENDPOINT" s3api put-bucket-cors --bucket "$UPLOADS" --cors-configuration file:///tmp/cors.json
cat > /tmp/lifecycle.json <<EOF
{"Rules":[{"ID":"expire-unprocessed-uploads","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"Days":1}}]}
EOF
# Not every S3 implementation supports lifecycle rules; the worker deletes processed uploads anyway.
aws --endpoint-url "$S3_ENDPOINT" s3api put-bucket-lifecycle-configuration --bucket "$UPLOADS" \
  --lifecycle-configuration file:///tmp/lifecycle.json \
  || echo "Lifecycle rule not supported by this S3 implementation; skipped"
