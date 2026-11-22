#!/usr/bin/env python3
"""Postfix pipe transport (ADR 0024): hand one received message to the ERIKA API.

Usage (master.cf): deliver.py <recipient> <sender>; the raw message arrives on stdin.
The API archives it write-once and answers 202 (POST /internal/intake/mail,
api/internal.yaml). Exit codes follow sysexits.h, which Postfix understands:
  0  delivered                 67 unknown recipient (bounce)
  69 message too large (bounce) 75 temporary failure (Postfix retries later)
"""
import sys
import urllib.error
import urllib.request

CONFIG_FILE = "/etc/erika-intake.conf"
EX_OK, EX_NOUSER, EX_UNAVAILABLE, EX_TEMPFAIL = 0, 67, 69, 75


def load_config():
    config = {}
    with open(CONFIG_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                config[key.strip()] = value.strip()
    return config


def main():
    recipient = sys.argv[1] if len(sys.argv) > 1 else ""
    sender = sys.argv[2] if len(sys.argv) > 2 else ""
    try:
        config = load_config()
        message = sys.stdin.buffer.read()
        request = urllib.request.Request(
            config["API_URL"].rstrip("/") + "/internal/intake/mail",
            data=message,
            headers={
                "Content-Type": "message/rfc822",
                "X-Intake-Secret": config["INTAKE_SECRET"],
                "X-Erika-Recipient": recipient,
                "X-Erika-Sender": sender,
            },
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=120):
            return EX_OK
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print("5.1.1 Recipient address unknown", file=sys.stderr)
            return EX_NOUSER
        if e.code == 413:
            print("5.3.4 Message too large", file=sys.stderr)
            return EX_UNAVAILABLE
        print("4.3.0 ERIKA intake answered HTTP %d" % e.code, file=sys.stderr)
        return EX_TEMPFAIL
    except (urllib.error.URLError, OSError, KeyError) as e:
        print("4.3.0 ERIKA intake not reachable: %s" % type(e).__name__, file=sys.stderr)
        return EX_TEMPFAIL


if __name__ == "__main__":
    sys.exit(main())
