#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 Diridium Technologies Inc.
#
# Installs the four OIE engine jars this plugin builds against into the local
# Maven repository, taken from the published OIE distribution for the engine
# version in the POM (mc.version). This is the same source CI uses.
#
# A sibling engine checkout is deliberately not used. A build of engine main
# stamps its jars with the last release's version, so installing from one puts
# unreleased APIs into ~/.m2 under the release's coordinates. A plugin compiled
# against those builds cleanly here and fails on a real server with
# NoSuchMethodError.
#
# This overwrites any com.mirth.connect jars already in ~/.m2 at the same
# version, for every project that uses them.
#
# The tarball is checked against the SHA-256 in the POM (oie.dist.sha256)
# before anything is taken from it. It and the extracted jars are cached under
# $OIE_DIST_CACHE, or ${XDG_CACHE_HOME:-~/.cache}/oie-dist, and reused on later
# runs. CI runs this script too.
#
# Usage:
#   ./scripts/install-engine-jars.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

# Plugin versions pinned, so the script does not depend on what the local Maven resolves.
HELP_PLUGIN="org.apache.maven.plugins:maven-help-plugin:3.5.1"
INSTALL_PLUGIN="org.apache.maven.plugins:maven-install-plugin:3.1.4"

pom_property() {
    (cd "$REPO_DIR" && mvn -q -N "$HELP_PLUGIN:evaluate" -Dexpression="$1" -DforceStdout)
}

# sha256sum on Linux; macOS ships shasum instead.
sha256_of() {
    if command -v sha256sum > /dev/null; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

MC_VERSION="$(pom_property mc.version)"
EXPECTED_SHA256="$(pom_property oie.dist.sha256)"
if [[ -z "$MC_VERSION" || -z "$EXPECTED_SHA256" ]]; then
    echo "error: could not read mc.version and oie.dist.sha256 from pom.xml" >&2
    exit 1
fi

TARBALL="oie_unix_${MC_VERSION//./_}.tar.gz"
URL="https://github.com/OpenIntegrationEngine/engine/releases/download/v${MC_VERSION}/${TARBALL}"
CACHE_DIR="${OIE_DIST_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/oie-dist}"
EXTRACT_DIR="$CACHE_DIR/$MC_VERSION"
mkdir -p "$EXTRACT_DIR"

if [[ ! -f "$CACHE_DIR/$TARBALL" ]]; then
    echo "downloading $URL"
    # Download under a temporary name and rename only on success, so an
    # interrupted transfer never leaves a truncated tarball in the cache.
    curl -fSL -o "$CACHE_DIR/$TARBALL.part" "$URL"
    mv "$CACHE_DIR/$TARBALL.part" "$CACHE_DIR/$TARBALL"
fi

ACTUAL_SHA256="$(sha256_of "$CACHE_DIR/$TARBALL")"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    echo "error: $CACHE_DIR/$TARBALL has SHA-256 $ACTUAL_SHA256, but pom.xml expects $EXPECTED_SHA256." >&2
    echo "Remove that file and run this script again to download it afresh." >&2
    exit 1
fi

tar xzf "$CACHE_DIR/$TARBALL" -C "$EXTRACT_DIR" \
    oie/server-lib/mirth-server.jar \
    oie/server-lib/mirth-client-core.jar \
    oie/server-lib/donkey/donkey-server.jar \
    oie/client-lib/mirth-client.jar

for pair in \
    "mirth-server:oie/server-lib/mirth-server.jar" \
    "mirth-client-core:oie/server-lib/mirth-client-core.jar" \
    "donkey-server:oie/server-lib/donkey/donkey-server.jar" \
    "mirth-client:oie/client-lib/mirth-client.jar"; do
    artifact="${pair%%:*}"
    jar_path="$EXTRACT_DIR/${pair#*:}"
    echo "installing ${artifact}-${MC_VERSION} from ${jar_path}"
    mvn -q "$INSTALL_PLUGIN:install-file" \
        -Dfile="$jar_path" \
        -DgroupId=com.mirth.connect \
        -DartifactId="$artifact" \
        -Dversion="$MC_VERSION" \
        -Dpackaging=jar
done

echo "done. 4 jars installed at version ${MC_VERSION}."
