#!/usr/bin/env bash
# Shared helper: writes backup results for node_exporter's textfile collector.
# The file is written atomically (tmp + mv), as the collector may read at any time.

# write_backup_metrics <dir> <kind> <type> <exit status> <start epoch>
write_backup_metrics() {
  local dir="$1" kind="$2" type="$3" status="$4" start="$5"
  local now file labels last_success ok
  now=$(date +%s)
  file="$dir/erika_backup_${kind//-/_}_${type}.prom"
  labels="kind=\"$kind\",type=\"$type\""
  mkdir -p "$dir"

  # Keep the previous success time when this run failed, so "last success" stays true.
  last_success=$(sed -n 's/^erika_backup_last_success_timestamp_seconds{.*} \([0-9]*\)$/\1/p' "$file" 2>/dev/null || true)
  if [[ "$status" -eq 0 ]]; then
    last_success=$now
    ok=1
  else
    ok=0
  fi

  {
    echo "# HELP erika_backup_last_run_timestamp_seconds Last time this backup job ran."
    echo "# TYPE erika_backup_last_run_timestamp_seconds gauge"
    echo "erika_backup_last_run_timestamp_seconds{$labels} $now"
    if [[ -n "$last_success" ]]; then
      echo "# HELP erika_backup_last_success_timestamp_seconds Last time this backup job succeeded."
      echo "# TYPE erika_backup_last_success_timestamp_seconds gauge"
      echo "erika_backup_last_success_timestamp_seconds{$labels} $last_success"
    fi
    echo "# HELP erika_backup_last_status 1 if the last run succeeded, 0 if it failed."
    echo "# TYPE erika_backup_last_status gauge"
    echo "erika_backup_last_status{$labels} $ok"
    echo "# HELP erika_backup_duration_seconds Duration of the last run."
    echo "# TYPE erika_backup_duration_seconds gauge"
    echo "erika_backup_duration_seconds{$labels} $((now - start))"
  } >"$file.tmp"
  mv "$file.tmp" "$file"
}
