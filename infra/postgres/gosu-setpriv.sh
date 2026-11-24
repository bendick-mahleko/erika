#!/bin/sh
# Drop-in for `gosu USER COMMAND...` as the official PostgreSQL entrypoint calls it, using
# util-linux setpriv instead of the gosu binary (ADR 0034). Same effect: run COMMAND as USER
# with USER's groups, replacing this process (no extra PID).
set -eu
if [ "$#" -lt 2 ]; then
  echo "usage: gosu USER COMMAND [ARGS...]" >&2
  exit 1
fi
user="$1"
shift
exec setpriv --reuid="$user" --regid="$user" --init-groups -- "$@"
