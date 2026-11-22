#!/usr/bin/env python3
"""Postfix policy service (ADR 0024): accept mail only for known intake addresses.

Postfix starts this script per connection (spawn) and talks the policy delegation
protocol on stdin/stdout: name=value lines, a blank line ends a request, and the reply
is "action=..." followed by a blank line. The lookup itself is an HTTP call to the API
(POST /internal/intake/recipients/check, api/internal.yaml).
"""
import json
import sys
import urllib.error
import urllib.request

CONFIG_FILE = "/etc/erika-intake.conf"


def load_config():
    config = {}
    with open(CONFIG_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                config[key.strip()] = value.strip()
    return config


def known(config, recipient):
    request = urllib.request.Request(
        config["API_URL"].rstrip("/") + "/internal/intake/recipients/check",
        data=json.dumps({"recipient": recipient}).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Intake-Secret": config["INTAKE_SECRET"]},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return bool(json.load(response).get("known"))


def decide(config, attributes):
    # Only the recipient step matters; everything else is left to the other restrictions.
    if attributes.get("protocol_state", "RCPT") != "RCPT":
        return "DUNNO"
    recipient = attributes.get("recipient", "")
    try:
        if known(config, recipient):
            return "DUNNO"
        return "REJECT 5.1.1 Recipient address unknown"
    except (urllib.error.URLError, OSError, ValueError, KeyError):
        # API down or misconfigured: the sender's server retries later.
        return "DEFER_IF_PERMIT 4.3.0 Service temporarily unavailable, please try again later"


def main():
    config = load_config()
    attributes = {}
    for raw in sys.stdin:
        line = raw.rstrip("\n")
        if line:
            if "=" in line:
                key, value = line.split("=", 1)
                attributes[key] = value
            continue
        sys.stdout.write("action=%s\n\n" % decide(config, attributes))
        sys.stdout.flush()
        attributes = {}


if __name__ == "__main__":
    main()
