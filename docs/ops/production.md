# Production: setup and operations

Background: [ADR 0027](../adr/0027-production-environment.md) (layout, access), [0028](../adr/0028-backups-and-restore.md) (backups), [0029](../adr/0029-monitoring-logs-alerts.md) (monitoring), [0030](../adr/0030-closed-beta.md) (beta).

```
                internet
   80/443/25 │            │ 80/443
        ┌────┴────┐  ┌────┴────┐
        │ app     │  │ ops     │   Prometheus, Alertmanager, Grafana, Loki,
        │ nbg1    │  │ fsn1    │   GlitchTip, Uptime Kuma (status page), CI runner
        │ .10     │  │ .30     │
        └────┬────┘  └────┬────┘
   private network 10.0.1.0/24 (TLS to PostgreSQL; metrics; logs; deploys)
        ┌────┴────┐
        │ db      │  PostgreSQL 16 + pgBackRest, LUKS volume, no public IP
        │ fsn1 .20│  outgoing traffic via NAT on app
        └─────────┘
   Object storage: project erika-prod (fsn1: originals, audit heads, uploads, derived)
                   project erika-backup (nbg1: pgBackRest, replicas; keys only on db)
```

## First-time setup

Do this once. It takes about half a day, plus DNS propagation.

1. **Hetzner projects.** Create `erika-prod` and `erika-backup`. In each:
   - an API token (`erika-prod` only);
   - an Object Storage key pair.

   Accept Hetzner's data-processing agreement in the account (see [docs/legal/README.md](../legal/README.md)).
2. **Terraform state bucket.** Create `erika-prod-tfstate` in `erika-backup` (nbg1), with versioning on.
3. **Secrets.**
   1. Create an age key pair for each administrator: `age-keygen -o ~/.config/sops/age/keys.txt`.
   2. Put the public keys in `infra/production/.sops.yaml`.
   3. Copy `secrets.example.yaml` to `secrets.sops.yaml`, fill it in (generate the values with the commands in the comments), then encrypt it: `sops -e -i infra/production/secrets.sops.yaml`.
   4. Print the age key and the pgBackRest cipher pass for the [offline recovery kit](#secrets).
4. **Infrastructure:**
   ```sh
   cd infra/terraform
   cp terraform.tfvars.example terraform.tfvars   # admin keys and IPs, server types (check prices)
   cp backend.hcl.example backend.hcl
   terraform init -backend-config=backend.hcl
   terraform apply
   terraform output dns_records
   ```
5. **DNS** at the registrar, from `dns_records`:

   | Name | Type | Value |
   |---|---|---|
   | `erika-app.de` | A, AAAA | app server |
   | `mx.erika-app.de` | A | app server (IPv4 only; PTR is set by Terraform) |
   | `in.erika-app.de` | MX 10 | `mx.erika-app.de` (intake addresses, ADR 0024) |
   | `grafana`, `errors`, `status` | A | ops server |
   | `erika-app.de` | TXT | SPF of the outgoing mail provider, e.g. `v=spf1 include:<provider> -all` |
   | `<selector>._domainkey` | TXT/CNAME | DKIM, from the mail provider |
   | `_dmarc` | TXT | `v=DMARC1; p=quarantine; rua=mailto:dmarc@erika-app.de` |
   | `in.erika-app.de` | TXT | `v=spf1 -all` (the intake domain never sends) |
   | `erika-app.de` | CAA | `0 issue "letsencrypt.org"` |

6. **Configuration.**
   1. Fill in `infra/ansible/group_vars/all.yml`: image repository, PostgreSQL image tag.
   2. Run:
      ```sh
      cd infra/ansible
      ansible-galaxy collection install -r requirements.yml
      terraform -chdir=../terraform output -raw ansible_inventory > inventory/production.yml
      ansible-playbook site.yml
      ```
   3. On the first run, set `erika_ci_runner_token` (one-time token from *Settings → Actions → Runners → New self-hosted runner*). It registers the runner on the ops server.
7. **GitHub.**
   1. Create the environment `production` with a required reviewer.
   2. Make sure the repository is private (ADR 0027).
   3. Create a fine-grained token with `read:packages` and store it as `erika_ghcr_token` in SOPS.
8. **GlitchTip.**
   1. Create an admin with `docker compose exec glitchtip-web ./manage.py createsuperuser`.
   2. Create the organisation "ERIKA" and the project "erika-backend".
   3. Put its DSN into `erika_sentry_dsn` and re-run Ansible for the app server: `--limit app`.
9. **Status page.** Set up the monitors in Uptime Kuma ([monitoring.md](monitoring.md#status-page)).
10. **First deployment.** Tag a release (`git tag v0.4.0 && git push --tags`) and approve the `production` job in GitHub Actions. Mail TLS: Caddy gets the certificate for `mx.` within a minute; then run `docker compose restart mail` once, so Postfix offers STARTTLS.
11. **Check the exit criteria** ([beta.md](beta.md#beta-gate)), including an alert test and a restore drill.

## Routine work

| What | How |
|---|---|
| Release | Tag `vX.Y.Z` → CI → staging → approve `production`. Checklist: [release-checklist.md](release-checklist.md). |
| Rollback | Automatic on a failed health check. Manually: `ssh erika@<app> "cd /opt/erika && ./deploy.sh <previous version>"` (admin key; migrations are backward compatible). |
| Configuration change | Edit in Git, then `ansible-playbook site.yml --limit <app\|db\|ops>`. |
| Invite a beta user | [beta.md](beta.md#invite-someone) |
| Validation pack update (NF-15) | [validation-pack-update.md](validation-pack-update.md) |
| Restore | [restore.md](restore.md) |
| Database shell | `ssh -J erika@<app> erika@10.0.1.20`, then `cd /opt/erika && docker compose exec postgres psql -U erika_owner -d erika` |
| PostgreSQL image update | New tag from the "PostgreSQL image" workflow → `erika_postgres_image_tag` → `ansible-playbook site.yml --limit db` (restarts the database briefly; announce it) |
| OS updates | Unattended upgrades with automatic reboots at 03:45. Containers restart with the daemon (`live-restore`). |

## Secrets

- All secrets are in `infra/production/secrets.sops.yaml`, encrypted for the admins' age keys. Ansible writes them to the servers as files with mode 0600.
- **Offline recovery kit** (printed, in a safe; risk R9):
  - the age private key;
  - the pgBackRest cipher pass;
  - the Hetzner account login and second factor;
  - the GitHub recovery codes.

  Without the cipher pass no database backup can be read.
- Rotating a secret: change it in SOPS, run Ansible for the affected server, then restart that stack. Special cases:
  - `ERIKA_TOTP_ENCRYPTION_KEY` can't be rotated without re-enrolling every user's second factor;
  - rotating `ERIKA_INVITE_SECRET` invalidates all open invitations.
