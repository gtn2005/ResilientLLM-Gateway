#!/bin/sh
set -eu
VERSION=3.9.11
SHA512=bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978
ROOT="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/apache-maven-$VERSION"
if [ ! -x "$ROOT/bin/mvn" ]; then
  mkdir -p "$(dirname "$ROOT")"
  ARCHIVE="$(mktemp)"
  trap 'rm -f "$ARCHIVE"' EXIT
  curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VERSION/apache-maven-$VERSION-bin.tar.gz" -o "$ARCHIVE"
  if command -v sha512sum >/dev/null 2>&1; then
    ACTUAL="$(sha512sum "$ARCHIVE" | cut -d ' ' -f 1)"
  else
    ACTUAL="$(shasum -a 512 "$ARCHIVE" | cut -d ' ' -f 1)"
  fi
  [ "$ACTUAL" = "$SHA512" ] || { echo 'Maven distribution checksum mismatch' >&2; exit 1; }
  tar -xzf "$ARCHIVE" -C "$(dirname "$ROOT")"
  rm -f "$ARCHIVE"
  trap - EXIT
fi
exec "$ROOT/bin/mvn" "$@"
