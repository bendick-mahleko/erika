#!/bin/sh
# Configures Postfix for ERIKA intake from environment variables and starts it with the
# ClamAV milter (ADR 0006, ADR 0024). Required:
#   ERIKA_INTAKE_MAIL_DOMAIN  e.g. in.erika-app.de (intake addresses: <token>@<domain>)
#   ERIKA_API_URL             e.g. http://api:8080 (internal network only)
#   ERIKA_INTAKE_MAIL_SECRET  shared secret, the same as the API's
# Optional: MAIL_HOSTNAME (HELO name, should match the MX/PTR record), CLAMAV_HOST,
#   CLAMAV_PORT, TLS_CERT/TLS_KEY (PEM files; STARTTLS is offered when both exist).
set -eu

: "${ERIKA_INTAKE_MAIL_DOMAIN:?}" "${ERIKA_API_URL:?}" "${ERIKA_INTAKE_MAIL_SECRET:?}"

umask 027
cat > /etc/erika-intake.conf <<EOF
API_URL=${ERIKA_API_URL}
INTAKE_SECRET=${ERIKA_INTAKE_MAIL_SECRET}
EOF
chown root:nogroup /etc/erika-intake.conf
chmod 0640 /etc/erika-intake.conf
umask 022

# --- ClamAV milter: rejects infected mail during the SMTP dialogue (NF-04). -------------
cat > /etc/clamav/clamav-milter.conf <<EOF
MilterSocket inet:7357@127.0.0.1
ClamdSocket tcp:${CLAMAV_HOST:-clamav}:${CLAMAV_PORT:-3310}
OnInfected Reject
OnFail Defer
RejectMsg Message rejected: virus found (%v)
AddHeader No
MaxFileSize 25M
Foreground no
User clamav
LogSyslog no
LogInfected Basic
EOF
mkdir -p /run/clamav && chown clamav:clamav /run/clamav
clamav-milter --config-file=/etc/clamav/clamav-milter.conf

# --- Postfix: receive-only for the intake domain. ----------------------------------------
postconf -e \
  "myhostname=${MAIL_HOSTNAME:-mx.${ERIKA_INTAKE_MAIL_DOMAIN}}" \
  "mydestination=" \
  "local_recipient_maps=" \
  "relay_domains=${ERIKA_INTAKE_MAIL_DOMAIN}" \
  "relay_recipient_maps=" \
  "transport_maps=static:erika:" \
  "erika_destination_recipient_limit=1" \
  "message_size_limit=26214400" \
  "mailbox_size_limit=0" \
  "smtpd_helo_required=yes" \
  "disable_vrfy_command=yes" \
  "smtpd_banner=\$myhostname ESMTP" \
  "smtpd_relay_restrictions=reject_non_fqdn_recipient, reject_unauth_destination" \
  "smtpd_recipient_restrictions=reject_non_fqdn_recipient, reject_unauth_destination, check_policy_service unix:private/erika-policy, permit" \
  "smtpd_milters=inet:127.0.0.1:7357" \
  "non_smtpd_milters=inet:127.0.0.1:7357" \
  "milter_default_action=tempfail" \
  "inet_protocols=ipv4" \
  "maillog_file=/dev/stdout" \
  "compatibility_level=3.6"

if [ -n "${TLS_CERT:-}" ] && [ -f "${TLS_CERT}" ] && [ -f "${TLS_KEY:-}" ]; then
  postconf -e "smtpd_tls_cert_file=${TLS_CERT}" "smtpd_tls_key_file=${TLS_KEY}" "smtpd_tls_security_level=may" \
    "smtpd_tls_protocols=>=TLSv1.2"
else
  postconf -e "smtpd_tls_security_level=none"
fi

# Policy lookup and the pipe to the API (both run as nobody, see /etc/erika-intake.conf).
postconf -M "erika-policy/unix=erika-policy unix - n n - 0 spawn user=nobody argv=/usr/bin/python3 /opt/erika/policy.py"
postconf -M "erika/unix=erika unix - n n - 10 pipe flags=R user=nobody argv=/usr/bin/python3 /opt/erika/deliver.py \${recipient} \${sender}"
postconf -M "postlog/unix-dgram=postlog unix-dgram n - n - 1 postlogd"

# Postfix copies these into its chroot; inside a container the host's are fine.
cp /etc/resolv.conf /etc/services /var/spool/postfix/etc/ 2>/dev/null || true
newaliases
exec postfix start-fg
