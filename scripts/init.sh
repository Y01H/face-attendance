#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f config/env.sh ]; then echo 'config/env.sh already exists; keeping it.'; exit 0; fi
umask 077
random_b64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }
admin_password=$(random_b64 24)
feature_key=$(random_b64 32)
local_password=$(random_b64 24)
sed -e "s|GENERATE_ADMIN_PASSWORD|$admin_password|" -e "s|GENERATE_FEATURE_KEY|$feature_key|" -e "s|GENERATE_LOCAL_PASSWORD|$local_password|" config/env.sh.example > config/env.sh
echo 'Created config/env.sh. Edit the CLOUD_DB settings before starting.'
echo 'Admin password and encryption key are in that file. Keep a secure backup.'
