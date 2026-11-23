output "app_ipv4" {
  description = "A record of the domain and of the mail host name; PTR is set to mail_hostname."
  value       = hcloud_primary_ip.app_v4.ip_address
}

output "app_ipv6" {
  description = "AAAA record of the domain (the mail server listens on IPv4 only, ADR 0024)."
  value       = hcloud_primary_ip.app_v6.ip_address
}

output "ops_ipv4" {
  description = "A records of grafana., errors. and status. subdomains."
  value       = hcloud_primary_ip.ops_v4.ip_address
}

output "pgdata_volume_device" {
  description = "Block device of the database volume, encrypted by Ansible (role luks_volume)."
  value       = hcloud_volume.pgdata.linux_device
}

output "dns_records" {
  description = "DNS records to create at the registrar (docs/ops/production.md#dns)."
  value = {
    "${var.domain}"         = { A = hcloud_primary_ip.app_v4.ip_address, AAAA = hcloud_primary_ip.app_v6.ip_address }
    "${var.mail_hostname}"  = { A = hcloud_primary_ip.app_v4.ip_address }
    "grafana.${var.domain}" = { A = hcloud_primary_ip.ops_v4.ip_address }
    "errors.${var.domain}"  = { A = hcloud_primary_ip.ops_v4.ip_address }
    "status.${var.domain}"  = { A = hcloud_primary_ip.ops_v4.ip_address }
  }
}

# terraform output -raw ansible_inventory > ../ansible/inventory/production.yml
output "ansible_inventory" {
  value = yamlencode({
    all = {
      vars = {
        ansible_user     = "erika"
        erika_domain     = var.domain
        erika_private_ip = { app = local.private_ip.app, db = local.private_ip.db, ops = local.private_ip.ops }
      }
      children = {
        app = { hosts = { "erika-app" = { ansible_host = hcloud_primary_ip.app_v4.ip_address, private_ip = local.private_ip.app } } }
        ops = { hosts = { "erika-ops" = { ansible_host = hcloud_primary_ip.ops_v4.ip_address, private_ip = local.private_ip.ops } } }
        db = {
          hosts = {
            "erika-db" = {
              ansible_host            = local.private_ip.db
              private_ip              = local.private_ip.db
              ansible_ssh_common_args = "-o ProxyJump=erika@${hcloud_primary_ip.app_v4.ip_address}"
              pgdata_device           = hcloud_volume.pgdata.linux_device
            }
          }
        }
      }
    }
  })
}
