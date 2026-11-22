#!/usr/bin/env bash
set -euo pipefail
umask 077

# Create a local CA and a TLS certificate, then apply the Ingress Secret.
# Run from the repository root with the intended kubectl context / KUBECONFIG:
#   bash scripts/kubernetes/create-local-tls.sh
#   NODE_EXTRA_CA_CERTS="$PWD/.local/cinemaabyss/tls/ca.crt" npm --prefix tests/postman run test:kubernetes
# The hostname must resolve to the local Ingress. No TLS verification is disabled.
# CA and server private keys stay in the output directory and must not be committed.

output_dir=".local/cinemaabyss/tls"
namespace="cinemaabyss"
hostname="cinemaabyss.example.com"
regenerate=false

usage() {
  cat <<'EOF'
Usage: create-local-tls.sh [--output-dir DIR] [--namespace NAME] [--hostname HOST] [--regenerate]

Defaults: .local/cinemaabyss/tls, namespace cinemaabyss, host cinemaabyss.example.com.
Existing valid certificates are reused. --regenerate replaces this helper's keys
and certificates; clients must then trust the new ca.crt. The target namespace
must already exist. kubectl uses the current context or KUBECONFIG.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir|--namespace|--hostname)
      [[ $# -ge 2 && -n "$2" ]] || { usage >&2; exit 2; }
      case "$1" in
        --output-dir) output_dir="$2" ;;
        --namespace) namespace="$2" ;;
        --hostname) hostname="$2" ;;
      esac
      shift 2
      ;;
    --regenerate) regenerate=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

[[ "$hostname" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]] || { printf 'Invalid hostname\n' >&2; exit 2; }
command -v openssl >/dev/null || { printf 'openssl is required\n' >&2; exit 1; }
command -v kubectl >/dev/null || { printf 'kubectl is required\n' >&2; exit 1; }
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"

files=(ca.key ca.crt server.key server.crt)
present=0
for file in "${files[@]}"; do
  if [[ -e "$output_dir/$file" ]]; then present=$((present + 1)); fi
done

if [[ "$regenerate" == false && "$present" -gt 0 ]]; then
  if [[ "$present" -ne 4 ]] ||
     ! openssl x509 -in "$output_dir/ca.crt" -checkend 86400 -noout >/dev/null 2>&1 ||
     ! openssl x509 -in "$output_dir/server.crt" -checkend 86400 -checkhost "$hostname" -noout >/dev/null 2>&1 ||
     ! openssl verify -CAfile "$output_dir/ca.crt" "$output_dir/server.crt" >/dev/null 2>&1; then
    printf 'Existing TLS files are incomplete, invalid or expire soon; use --regenerate to replace them.\n' >&2
    exit 1
  fi
else
  work_dir="$(mktemp -d "$output_dir/.generate.XXXXXX")"
  trap 'rm -rf "$work_dir"' EXIT
  cat > "$work_dir/ca.cnf" <<'EOF'
[req]
prompt = no
distinguished_name = dn
x509_extensions = ca
[dn]
CN = CinemaAbyss local development CA
[ca]
basicConstraints = critical,CA:true,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF
  cat > "$work_dir/server.cnf" <<EOF
[req]
prompt = no
distinguished_name = dn
[dn]
CN = $hostname
[server]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:$hostname
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
EOF
  openssl req -new -x509 -newkey rsa:2048 -nodes -sha256 -days 365 \
    -config "$work_dir/ca.cnf" -keyout "$work_dir/ca.key" -out "$work_dir/ca.crt" 2>/dev/null
  openssl req -new -newkey rsa:2048 -nodes -sha256 \
    -config "$work_dir/server.cnf" -keyout "$work_dir/server.key" -out "$work_dir/server.csr" 2>/dev/null
  openssl x509 -req -sha256 -days 90 -in "$work_dir/server.csr" \
    -CA "$work_dir/ca.crt" -CAkey "$work_dir/ca.key" -CAcreateserial \
    -extfile "$work_dir/server.cnf" -extensions server -out "$work_dir/server.crt" 2>/dev/null
  for file in "${files[@]}"; do mv "$work_dir/$file" "$output_dir/$file"; done
fi
chmod 600 "$output_dir/ca.key" "$output_dir/server.key"
chmod 644 "$output_dir/ca.crt" "$output_dir/server.crt"

kubectl -n "$namespace" create secret tls cinemaabyss-tls \
  --cert="$output_dir/server.crt" --key="$output_dir/server.key" \
  --dry-run=client -o yaml | kubectl apply -f -
printf 'Local TLS configured for %s. Trust this CA: %s/ca.crt\n' "$hostname" "$output_dir"
