#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${CI_NAMESPACE:?CI_NAMESPACE is required}"
: "${JENKINS_HOST:?JENKINS_HOST is required}"

envsubst '${CI_NAMESPACE} ${JENKINS_HOST}' \
  < "${script_dir}/values.yaml.tpl" \
  > "${script_dir}/values.yaml"

if rg -n '\$\{(CI_NAMESPACE|JENKINS_HOST)\}' "${script_dir}/values.yaml" >/dev/null; then
  echo 'values.yaml still contains unresolved variables' >&2
  exit 1
fi
