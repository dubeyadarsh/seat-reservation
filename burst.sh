#!/usr/bin/env sh
# One-command on-sale stampede against a deployed instance.
#   ./burst.sh https://seat-reservation-67bc.onrender.com
#   ./burst.sh <BASE_URL> --requests 5000 --concurrency 500
# Needs Node 18+ and ADMIN_SECRET (environment variable or ./.env). Exit code 0 only if every check passes.
set -eu

if [ "$#" -lt 1 ]; then
  echo "Usage: ./burst.sh <BASE_URL> [--requests N] [--concurrency N] [--hot-seats N] [--users N]" >&2
  exit 2
fi

cd "$(dirname "$0")"
exec node burst/burst.mjs "$@"
