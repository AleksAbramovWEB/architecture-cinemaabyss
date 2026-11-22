#!/usr/bin/env bash
set -euo pipefail

timeout_seconds="${READINESS_TIMEOUT_SECONDS:-180}"
if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  printf 'READINESS_TIMEOUT_SECONDS must be a positive integer\n' >&2
  exit 2
fi

urls=(
  'http://127.0.0.1:8080/health'
  'http://127.0.0.1:8081/api/movies/health'
  'http://127.0.0.1:8082/api/events/health'
  'http://127.0.0.1:8000/health'
  'http://127.0.0.1:8000/api/movies'
)
deadline=$((SECONDS + timeout_seconds))

while true; do
  all_ready=true
  for url in "${urls[@]}"; do
    if ! curl --fail --silent --max-time 3 "$url" > /dev/null; then
      all_ready=false
      break
    fi
  done
  if [[ "$all_ready" == true ]]; then
    printf 'All HTTP services are ready\n'
    exit 0
  fi
  if ((SECONDS >= deadline)); then
    printf 'HTTP readiness timed out after %s seconds; last unavailable endpoint: %s\n' "$timeout_seconds" "$url" >&2
    exit 1
  fi
  printf 'Waiting for %s\n' "$url"
  sleep 2
done
