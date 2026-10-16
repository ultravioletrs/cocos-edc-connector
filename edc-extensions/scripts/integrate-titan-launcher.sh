#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 /path/to/edc-launchers" >&2
    exit 2
fi

LAUNCHER_ROOT="$(cd "$1" && pwd)"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SETTINGS="$LAUNCHER_ROOT/settings.gradle.kts"
CATALOG="$LAUNCHER_ROOT/gradle/libs.versions.toml"
COCOS_TARGET="$LAUNCHER_ROOT/extensions/cocos"

[[ -f "$SETTINGS" ]] || { echo "Missing launcher settings: $SETTINGS" >&2; exit 1; }
[[ -f "$CATALOG" ]] || { echo "Missing launcher catalog: $CATALOG" >&2; exit 1; }
[[ ! -e "$COCOS_TARGET" ]] || { echo "Refusing to overwrite existing $COCOS_TARGET" >&2; exit 1; }

cp -R "$REPO_ROOT/extensions/cocos" "$COCOS_TARGET"

cat >> "$SETTINGS" <<'EOF'

// Cocos confidential-computing extensions
include(":extensions:cocos:cocos-spi")
include(":extensions:cocos:cocos-cli")
include(":extensions:cocos:cocos-computation-api")
include(":extensions:cocos:cocos-orchestrator")
include(":extensions:cocos:cocos-attestation-credential-service")
include(":extensions:cocos:cocos-data-sink")

project(":extensions:cocos:cocos-spi").projectDir = file("extensions/cocos/cocos-spi")
project(":extensions:cocos:cocos-cli").projectDir = file("extensions/cocos/cocos-cli")
project(":extensions:cocos:cocos-computation-api").projectDir = file("extensions/cocos/cocos-computation-api")
project(":extensions:cocos:cocos-orchestrator").projectDir = file("extensions/cocos/cocos-orchestrator")
project(":extensions:cocos:cocos-attestation-credential-service").projectDir = file("extensions/cocos/cocos-attestation-credential-service")
project(":extensions:cocos:cocos-data-sink").projectDir = file("extensions/cocos/cocos-data-sink")
EOF

tmp_catalog="$(mktemp)"
awk '
BEGIN {
    additions = "edc-core-spi             = { module = \"org.eclipse.edc:core-spi\", version.ref = \"edc\" }\n" \
        "edc-control-plane-spi    = { module = \"org.eclipse.edc:control-plane-spi\", version.ref = \"edc\" }\n" \
        "edc-http-spi             = { module = \"org.eclipse.edc:http-spi\", version.ref = \"edc\" }\n" \
        "edc-web-spi              = { module = \"org.eclipse.edc:web-spi\", version.ref = \"edc\" }\n" \
        "edc-data-plane-spi       = { module = \"org.eclipse.edc:data-plane-spi\", version.ref = \"edc\" }\n" \
        "edc-decentralized-claims-spi = { module = \"org.eclipse.edc:decentralized-claims-spi\", version.ref = \"edc\" }\n" \
        "jackson-databind         = { module = \"com.fasterxml.jackson.core:jackson-databind\", version = \"2.17.3\" }\n" \
        "grpc-netty-shaded        = { module = \"io.grpc:grpc-netty-shaded\", version = \"1.58.0\" }\n" \
        "grpc-protobuf            = { module = \"io.grpc:grpc-protobuf\", version = \"1.58.0\" }\n" \
        "grpc-stub                = { module = \"io.grpc:grpc-stub\", version = \"1.58.0\" }\n" \
        "javax-annotation-api     = { module = \"javax.annotation:javax.annotation-api\", version = \"1.3.2\" }\n" \
        "junit-jupiter-api        = { module = \"org.junit.jupiter:junit-jupiter-api\", version = \"5.10.0\" }\n" \
        "junit-platform-launcher  = { module = \"org.junit.platform:junit-platform-launcher\", version = \"1.10.0\" }\n" \
        "mockito-core             = { module = \"org.mockito:mockito-core\", version = \"5.11.0\" }\n" \
        "assertj                  = { module = \"org.assertj:assertj-core\", version = \"3.26.3\" }\n" \
        "jakarta-rsApi            = { module = \"jakarta.ws.rs:jakarta.ws.rs-api\", version = \"3.1.0\" }\n" \
        "jersey-common            = { module = \"org.glassfish.jersey.core:jersey-common\", version = \"3.1.7\" }\n"
}
/^\[plugins\]$/ && !inserted { printf "\n# Cocos extension dependencies\n%s\n", additions; inserted = 1 }
{ print }
END { if (!inserted) exit 1 }
' "$CATALOG" > "$tmp_catalog"
mv "$tmp_catalog" "$CATALOG"

echo "Cocos modules staged in $LAUNCHER_ROOT"
echo "Build with: $LAUNCHER_ROOT/build-images.sh"
