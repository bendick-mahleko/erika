# 0012. SeaweedFS instead of MinIO for local S3

- Status: Accepted
- Date: 2026-11-22

## Context
The design lists MinIO for local development. MinIO no longer publishes community images on Docker Hub (`minio/minio` returns 404), so it can't be pulled reproducibly.

## Decision
SeaweedFS (`chrislusf/seaweedfs:4.50`, `weed mini`) is the local and test S3 endpoint, with identities in `infra/local/seaweedfs/s3.json`:
- `seaweed_admin` creates the bucket with Object Lock (`s3-init` service, test set-up);
- `erika-dev`, the app key, is limited to `erika-originals` and can't create buckets.

Verified on 2026-11-22: `create-bucket --object-lock-enabled-for-bucket` succeeds, `get-object-lock-configuration` reports `Enabled`, and the app key gets AccessDenied on CreateBucket.

## Consequences
The app uses only the standard S3 API, so production is unaffected. Because SeaweedFS's Object Lock may behave differently from Hetzner's, Stage 4 archive tests also run against a staging bucket (see 0013).
