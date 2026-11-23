variable "hcloud_token" {
  description = "API token of the Hetzner Cloud project 'erika-prod' (read/write)."
  type        = string
  sensitive   = true
}

variable "domain" {
  description = "Public domain of the web app, e.g. erika-app.de."
  type        = string
}

variable "mail_hostname" {
  description = "Host name of the inbound mail server (MX target, PTR of the app server), e.g. mx.erika-app.de."
  type        = string
}

variable "admin_ssh_keys" {
  description = "Public SSH keys of the administrators (name => key). Key-only login; no passwords."
  type        = map(string)
}

variable "admin_cidrs" {
  description = <<-EOT
    Networks allowed to reach SSH on the app and ops servers: the administrators' fixed IPs.
    Deployments run on a self-hosted GitHub runner on the ops server and reach the app server
    over the private network, so no CI IP ranges are needed (ADR 0027).
  EOT
  type        = list(string)

  validation {
    condition     = length(var.admin_cidrs) > 0 && !contains(var.admin_cidrs, "0.0.0.0/0")
    error_message = "List at least one admin network, and never 0.0.0.0/0."
  }
}

variable "image" {
  description = "Operating system image of all servers."
  type        = string
  default     = "ubuntu-24.04"
}

# Server types: check current names and prices first (`hcloud server-type list`);
# Hetzner renamed and repriced types during 2025/2026 (Software Design §8).
variable "app_server_type" {
  description = "App server: 4–8 vCPU, 16 GB RAM (Caddy, Next.js, API, worker, Postfix, ClamAV)."
  type        = string
}

variable "db_server_type" {
  description = "Database server: 4 vCPU, 8 GB RAM."
  type        = string
}

variable "ops_server_type" {
  description = "Ops server: 2–4 vCPU, 4–8 GB RAM (Prometheus, Grafana, Loki, GlitchTip, Uptime Kuma)."
  type        = string
}

variable "app_location" {
  type    = string
  default = "nbg1"
}

variable "db_location" {
  type    = string
  default = "fsn1"
}

variable "ops_location" {
  description = "A different data centre than the app server, so 'site down' is seen from outside."
  type        = string
  default     = "fsn1"
}

variable "pgdata_volume_size" {
  description = "Size of the encrypted database volume in GB (data + WAL spool)."
  type        = number
  default     = 50
}

# Object storage (S3 API). Keys are created in the Hetzner console (not available in the API).
variable "bucket_prefix" {
  description = "Prefix of all bucket names (bucket names are unique across Hetzner), e.g. erika-prod."
  type        = string
}

variable "s3_primary_location" {
  description = "Location of the archive buckets (originals etc.)."
  type        = string
  default     = "fsn1"
}

variable "s3_backup_location" {
  description = "Second location for backups and replicas (NF-05)."
  type        = string
  default     = "nbg1"
}

variable "s3_primary_access_key" {
  description = "S3 key of the project 'erika-prod' (the app's buckets)."
  type        = string
  sensitive   = true
}

variable "s3_primary_secret_key" {
  type      = string
  sensitive = true
}

variable "s3_backup_access_key" {
  description = "S3 key of the separate project 'erika-backup'; used only on the database server."
  type        = string
  sensitive   = true
}

variable "s3_backup_secret_key" {
  type      = string
  sensitive = true
}

variable "replica_retention_days" {
  description = "Default COMPLIANCE retention on the replica buckets: protects copies against deletion with stolen keys."
  type        = number
  default     = 30
}
