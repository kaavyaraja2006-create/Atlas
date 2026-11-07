#!/usr/bin/env bash
# Downloads a pinned release binary from GitHub and verifies it against the release's
# published checksum before unpacking. Usage: scripts/ci/fetch-tool.sh <tool> <dest-dir>
# Versions are pinned here (each at least two weeks old when chosen); bump them deliberately.
set -euo pipefail
tool="$1"; dest="$2"; mkdir -p "$dest"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
gh="https://github.com"
fetch() { curl -sSfL --retry 3 -o "$tmp/$2" "$1/$2"; }
verify() {  # verify <sumfile> <file> <sha256|sha512>
  (cd "$tmp" && grep -E "[ *]$2\$" "$1" | head -1 | sed 's/\*//' | "${3}sum" -c -)
}
case "$tool" in
  gitleaks) v=8.21.2; b="$gh/gitleaks/gitleaks/releases/download/v$v"; f="gitleaks_${v}_linux_x64.tar.gz"
    fetch "$b" "$f"; fetch "$b" "gitleaks_${v}_checksums.txt"; verify "gitleaks_${v}_checksums.txt" "$f" sha256
    tar -xzf "$tmp/$f" -C "$dest" gitleaks ;;
  k6) v=0.54.0; b="$gh/grafana/k6/releases/download/v$v"; f="k6-v$v-linux-amd64.tar.gz"
    fetch "$b" "$f"; fetch "$b" "k6-v$v-checksums.txt"; verify "k6-v$v-checksums.txt" "$f" sha256
    tar -xzf "$tmp/$f" -C "$dest" --strip-components=1 "k6-v$v-linux-amd64/k6" ;;
  trivy) v=0.76.1; b="$gh/aquasecurity/trivy/releases/download/v$v"; f="trivy_${v}_Linux-64bit.tar.gz"
    fetch "$b" "$f"; fetch "$b" "trivy_${v}_checksums.txt"; verify "trivy_${v}_checksums.txt" "$f" sha256
    tar -xzf "$tmp/$f" -C "$dest" trivy ;;
  caddy) v=2.11.6; b="$gh/caddyserver/caddy/releases/download/v$v"; f="caddy_${v}_linux_amd64.tar.gz"
    fetch "$b" "$f"; fetch "$b" "caddy_${v}_checksums.txt"; verify "caddy_${v}_checksums.txt" "$f" sha512
    tar -xzf "$tmp/$f" -C "$dest" caddy ;;
  promtool) v=3.5.0; b="$gh/prometheus/prometheus/releases/download/v$v"; f="prometheus-$v.linux-amd64.tar.gz"
    fetch "$b" "$f"; fetch "$b" sha256sums.txt; verify sha256sums.txt "$f" sha256
    tar -xzf "$tmp/$f" -C "$dest" --strip-components=1 "prometheus-$v.linux-amd64/promtool" ;;
  hadolint) v=2.12.0; b="$gh/hadolint/hadolint/releases/download/v$v"; f="hadolint-Linux-x86_64"
    fetch "$b" "$f"; fetch "$b" "$f.sha256"; verify "$f.sha256" "$f" sha256
    install -m 0755 "$tmp/$f" "$dest/hadolint" ;;
  actionlint) v=1.7.12; b="$gh/rhysd/actionlint/releases/download/v$v"; f="actionlint_${v}_linux_amd64.tar.gz"
    fetch "$b" "$f"; fetch "$b" "actionlint_${v}_checksums.txt"; verify "actionlint_${v}_checksums.txt" "$f" sha256
    tar -xzf "$tmp/$f" -C "$dest" actionlint ;;
  tofu) v=1.10.7; b="$gh/opentofu/opentofu/releases/download/v$v"; f="tofu_${v}_linux_amd64.tar.gz"
    fetch "$b" "$f"; fetch "$b" "tofu_${v}_SHA256SUMS"; verify "tofu_${v}_SHA256SUMS" "$f" sha256
    tar -xzf "$tmp/$f" -C "$dest" tofu ;;
  *) echo "unknown tool: $tool" >&2; exit 2 ;;
esac
echo "$tool $v -> $dest"
