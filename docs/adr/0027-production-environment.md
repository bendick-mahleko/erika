# 0027. Production environment as code

- Status: Accepted
- Date: 2026-11-23

## Context
Stage 7 needs a production setup that matches Software Design §8: an app server in Nuremberg, a database server in Falkenstein reachable only over the private network, object storage in Germany and backups at another location. The firewall opens only 80, 443 and 25, and SSH is key-only and restricted. A solo developer must be able to rebuild everything from the repository (risk R9).

## Decision
**Tools.**
- Terraform (`infra/terraform`) creates the cloud resources: hcloud provider, plus the AWS provider pointed at Hetzner's S3 endpoints for buckets.
- Ansible (`infra/ansible`) configures the servers.
- Docker Compose files in `infra/production/{app,db,ops}` run the containers.
- Secrets live in one SOPS + age file, `infra/production/secrets.sops.yaml`. Ansible decrypts it on the admin's machine and writes `.env` files with mode 0600 on the servers.

**Servers.**

| Server | Location | Public address | Runs |
|---|---|---|---|
| app | nbg1 | Fixed IPv4 and IPv6 (primary IPs, so DNS survives a rebuild); PTR = MX host name | Caddy, web, API, worker, Postfix, ClamAV, Alloy, node_exporter. It is also the NAT gateway of the private network. |
| db | fsn1 | **None.** Outgoing traffic leaves through the app server: a network route `0.0.0.0/0 → 10.0.1.10`, masquerading on the app server. | PostgreSQL 16 + pgBackRest (ADR 0028) on a LUKS-encrypted volume. |
| ops | fsn1 | Fixed IPv4 | Monitoring, GlitchTip, status page (ADR 0029), and a self-hosted CI runner. |

- The private network is `10.0.1.0/24`: app `.10`, db `.20`, ops `.30`.
- Services meant for other servers (actuator, worker metrics, Caddy metrics, node_exporter, PostgreSQL, Loki) bind to the private IP only.

**Firewall and access.**
- Hetzner Cloud firewalls on the public interfaces:
  - app: 80, 443, 25 and ICMP from anywhere; 22 from the admins' fixed networks only;
  - ops: 80, 443 and ICMP from anywhere; 22 from the admins only.
- SSH is key-only, no root login, `AllowUsers erika`.
- **Deployments come from a self-hosted GitHub runner on the ops server.** It reaches the app server over the private network. Its key is restricted with a forced command to `deploy.sh <version>`, and `deploy.sh` accepts nothing but a version number.
  - This replaces "SSH restricted to the CI runner's IP addresses" from the design: GitHub-hosted runners use thousands of changing IP ranges, so that rule would effectively open SSH to the internet.
  - The repository must stay private: a public repository would let pull requests run code on the runner.
- Before switching images, `deploy.sh` asks the database server for a WAL-archiving check. That key is likewise restricted to that one command.

**Database traffic** uses TLS, as the design says "TLS everywhere".
- PostgreSQL has a self-signed certificate made by Ansible, and `pg_hba.conf` accepts the app only with `hostssl` from `10.0.1.10`.
- The app connects with `sslmode=require`, which encrypts but doesn't verify the certificate; the private network limits who can connect.
- PostgreSQL never logs statement parameters (`log_parameter_max_length=0`), because they can contain invoice content.

**Staging** stays a single server (unchanged), but now uses the same PostgreSQL image with WAL archiving, so restore drills run there first.

## Consequences
- `terraform apply` followed by `ansible-playbook site.yml` rebuilds the environment. DNS records come from `terraform output dns_records`. App releases are deployed only by CI.
- CI checks everything in `infra/` on every build: `terraform validate`, `ansible-lint` (production profile), shellcheck, promtool, amtool, `caddy validate`, Loki/Alloy/Blackbox config checks.
- Hetzner's object storage keys can't be created through the API: they are made in the console and stored in SOPS.
- Server types are variables without defaults, because Hetzner renamed and repriced types in 2025/2026. The admin picks them with current prices.
- The volume's LUKS key file is on the database server's root disk. It protects detached volumes and snapshots, not a running, compromised server. Backups are encrypted separately.
- The database server depends on the app server for outgoing traffic, i.e. packages and backups. If the app server is down, WAL archiving queues locally (bounded) and the alerts fire.
