#!/bin/sh
# Fails if an app file is larger than the limit (M-NF-04: app size < 60 MB).
# Usage: tool/check_size.sh <file> [limit in MB, default 60]
set -eu
file="$1"
limit_mb="${2:-60}"
bytes=$(wc -c < "$file")
mb=$(awk "BEGIN { printf \"%.1f\", $bytes / 1048576 }")
echo "$file: $mb MB (limit $limit_mb MB)"
if [ "$bytes" -gt $((limit_mb * 1048576)) ]; then
  echo "::error::$file is $mb MB, over the $limit_mb MB limit (M-NF-04)" >&2
  exit 1
fi
