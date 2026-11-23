#!/usr/bin/env python3
"""Creates a closed-beta invitation link (ADR 0030).

Usage (on the app server, in /opt/erika):
    ./invite.py anna@example.de            # valid for 21 days
    ./invite.py anna@example.de --days 7

Reads ERIKA_INVITE_SECRET and ERIKA_DOMAIN from the environment or from ./.env.
Same format as de.erika.identity.BetaInvitations (cross-checked by BetaInvitationsTest):
    v1.<base64url("<email lowercased>|<expiry epoch seconds>")>.<base64url(HMAC-SHA256("v1.<payload>"))>
The link only works for this email address. Send it personally; it is not logged anywhere.
"""
import argparse
import base64
import hashlib
import hmac
import os
import sys
import time
import urllib.parse


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def create(secret: str, email: str, expires_epoch: int) -> str:
    payload = b64(f"{email.strip().lower()}|{expires_epoch}".encode("utf-8"))
    signature = hmac.new(secret.encode("utf-8"), f"v1.{payload}".encode("utf-8"), hashlib.sha256).digest()
    return f"v1.{payload}.{b64(signature)}"


def read_env(path: str = ".env") -> dict:
    values = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def main() -> int:
    parser = argparse.ArgumentParser(description="Create an ERIKA beta invitation link")
    parser.add_argument("email")
    parser.add_argument("--days", type=int, default=21, help="validity in days (default 21)")
    parser.add_argument("--expires", type=int, help=argparse.SUPPRESS)  # fixed epoch, for tests
    args = parser.parse_args()

    env = {**read_env(), **os.environ}
    secret = env.get("ERIKA_INVITE_SECRET", "")
    domain = env.get("ERIKA_DOMAIN", "erika-app.de")
    if len(secret) < 32:
        print("ERIKA_INVITE_SECRET is missing or shorter than 32 characters", file=sys.stderr)
        return 1
    if "@" not in args.email:
        print("Not an email address", file=sys.stderr)
        return 1

    expires = args.expires or int(time.time()) + args.days * 86400
    code = create(secret, args.email, expires)
    query = urllib.parse.urlencode({"invite": code})
    print(f"https://{domain}/signup?{query}")
    print(f"valid until {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime(expires))}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
