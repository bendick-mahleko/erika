# syntax=docker/dockerfile:1
# Inbound mail for intake addresses (IN-01, ADR 0006, ADR 0024): Postfix + ClamAV milter,
# a policy lookup and a pipe to the ERIKA API. Receive-only: it never sends mail.
# Build context: repository root.
FROM debian:bookworm-slim
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      postfix clamav-milter python3 ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY infra/mail/policy.py infra/mail/deliver.py /opt/erika/
COPY infra/mail/entrypoint.sh /usr/local/bin/erika-mail-entrypoint
RUN chmod 0755 /opt/erika/*.py /usr/local/bin/erika-mail-entrypoint
EXPOSE 25
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=10 \
  CMD postfix status >/dev/null 2>&1 || exit 1
ENTRYPOINT ["/usr/local/bin/erika-mail-entrypoint"]
