#!/bin/bash
set -e

# Pushes the images built by build-image.sh to Docker Hub and joins them into a
# single multi-architecture tag. It builds nothing.
#
# Run this by hand, from a machine holding the credential.

# The image tag defaults to the Maven project version so a build with no argument
# is labelled with what it actually contains. Read from the root pom rather than
# by running Maven, since the build itself runs Maven inside the image and the
# host is not required to have it installed.
project_version() {
    awk '
        /<parent>/ { in_parent = 1 }
        /<\/parent>/ { in_parent = 0; next }
        in_parent { next }
        /<modules>|<properties>|<dependencies>|<build>|<profiles>/ { exit }
        match($0, /<version>[^<]+<\/version>/) {
            print substr($0, RSTART + 9, RLENGTH - 19)
            exit
        }
    ' "$1"
}

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

VERSION=${1:-$(project_version "${SCRIPT_DIR}/pom.xml")}
if [ -z "$VERSION" ]; then
    echo "Could not read the project version from ${SCRIPT_DIR}/pom.xml. Pass one explicitly." >&2
    exit 1
fi

IMAGE=${IMAGE:-philterd/arbiter}
ARCHES=${ARCHES:-"amd64 arm64"}

for arch in $ARCHES; do
    docker push "${IMAGE}:${VERSION}-${arch}"
done

# Joins the pushed per-architecture images under one tag, in the registry.
sources=""
for arch in $ARCHES; do
    sources="${sources} ${IMAGE}:${VERSION}-${arch}"
done

docker buildx imagetools create -t "${IMAGE}:${VERSION}" ${sources}

echo
echo "Pushed ${IMAGE}:${VERSION} for ${ARCHES}"
