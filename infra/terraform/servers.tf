# Production layout (Software Design §8, ADR 0027):
#   app  (nbg1)  public IPv4/IPv6, Caddy + app + Postfix; NAT gateway for the private network
#   db   (fsn1)  NO public IP; reachable only over the private network; encrypted volume
#   ops  (fsn1)  public IPv4/IPv6, monitoring + status page + self-hosted CI runner

provider "hcloud" {
  token = var.hcloud_token
}

locals {
  private_ip = {
    app = "10.0.1.10"
    db  = "10.0.1.20"
    ops = "10.0.1.30"
  }
  labels = { project = "erika", environment = "production" }
}

resource "hcloud_ssh_key" "admin" {
  for_each   = var.admin_ssh_keys
  name       = "erika-${each.key}"
  public_key = each.value
}

resource "hcloud_network" "erika" {
  name     = "erika-prod"
  ip_range = "10.0.0.0/16"
  labels   = local.labels
}

resource "hcloud_network_subnet" "servers" {
  network_id   = hcloud_network.erika.id
  type         = "cloud"
  network_zone = "eu-central" # fsn1, nbg1 and hel1 share one private network zone
  ip_range     = "10.0.1.0/24"
}

# The database server has no public IP: its outgoing traffic (packages, backups to object
# storage) leaves through the app server, which masquerades the private network.
resource "hcloud_network_route" "default_via_app" {
  network_id  = hcloud_network.erika.id
  destination = "0.0.0.0/0"
  gateway     = local.private_ip.app
}

# Fixed public addresses, so DNS (A, AAAA, MX) survives a server rebuild.
resource "hcloud_primary_ip" "app_v4" {
  name        = "erika-app-v4"
  type        = "ipv4"
  location    = var.app_location
  auto_delete = false
  labels      = local.labels
}

resource "hcloud_primary_ip" "app_v6" {
  name        = "erika-app-v6"
  type        = "ipv6"
  location    = var.app_location
  auto_delete = false
  labels      = local.labels
}

resource "hcloud_primary_ip" "ops_v4" {
  name        = "erika-ops-v4"
  type        = "ipv4"
  location    = var.ops_location
  auto_delete = false
  labels      = local.labels
}

resource "hcloud_server" "app" {
  name         = "erika-app"
  server_type  = var.app_server_type
  image        = var.image
  location     = var.app_location
  ssh_keys     = [for k in hcloud_ssh_key.admin : k.id]
  firewall_ids = [hcloud_firewall.app.id]
  labels       = merge(local.labels, { role = "app" })
  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    hostname     = "erika-app"
    admin_keys   = values(var.admin_ssh_keys)
    private_only = false
  })

  public_net {
    ipv4_enabled = true
    ipv4         = hcloud_primary_ip.app_v4.id
    ipv6_enabled = true
    ipv6         = hcloud_primary_ip.app_v6.id
  }

  network {
    network_id = hcloud_network.erika.id
    ip         = local.private_ip.app
  }

  delete_protection  = true
  rebuild_protection = true
  depends_on         = [hcloud_network_subnet.servers]
}

resource "hcloud_server" "db" {
  name        = "erika-db"
  server_type = var.db_server_type
  image       = var.image
  location    = var.db_location
  ssh_keys    = [for k in hcloud_ssh_key.admin : k.id]
  labels      = merge(local.labels, { role = "db" })
  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    hostname     = "erika-db"
    admin_keys   = values(var.admin_ssh_keys)
    private_only = true
  })

  # No public interface at all (design §8: "The database has no public IP address").
  public_net {
    ipv4_enabled = false
    ipv6_enabled = false
  }

  network {
    network_id = hcloud_network.erika.id
    ip         = local.private_ip.db
  }

  delete_protection  = true
  rebuild_protection = true
  depends_on         = [hcloud_network_subnet.servers, hcloud_network_route.default_via_app]
}

resource "hcloud_volume" "pgdata" {
  name              = "erika-pgdata"
  size              = var.pgdata_volume_size
  location          = var.db_location
  automount         = false # Ansible encrypts it with LUKS before first use
  delete_protection = true
  labels            = local.labels
}

resource "hcloud_volume_attachment" "pgdata" {
  volume_id = hcloud_volume.pgdata.id
  server_id = hcloud_server.db.id
  automount = false
}

resource "hcloud_server" "ops" {
  name         = "erika-ops"
  server_type  = var.ops_server_type
  image        = var.image
  location     = var.ops_location
  ssh_keys     = [for k in hcloud_ssh_key.admin : k.id]
  firewall_ids = [hcloud_firewall.ops.id]
  labels       = merge(local.labels, { role = "ops" })
  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    hostname     = "erika-ops"
    admin_keys   = values(var.admin_ssh_keys)
    private_only = false
  })

  public_net {
    ipv4_enabled = true
    ipv4         = hcloud_primary_ip.ops_v4.id
    ipv6_enabled = false
  }

  network {
    network_id = hcloud_network.erika.id
    ip         = local.private_ip.ops
  }

  depends_on = [hcloud_network_subnet.servers]
}

# PTR record of the mail server's address (HELO name = MX host name, ADR 0024).
resource "hcloud_rdns" "app_v4" {
  primary_ip_id = hcloud_primary_ip.app_v4.id
  ip_address    = hcloud_primary_ip.app_v4.ip_address
  dns_ptr       = var.mail_hostname
}
