#!/bin/bash
set -e

# Builds the Arbiter Docker image for amd64 and arm64. Pushing it is a separate,
# manual step: see push-image.sh.
#
# Each architecture is built and loaded under its own tag, so both are here to
# run and test. push-image.sh pushes those tags and joins them into one
# multi-architecture tag.

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

# The default builder cannot cross-build, so use a container builder.
docker buildx inspect arbiter-builder > /dev/null 2>&1 ||
    docker buildx create --name arbiter-builder --driver docker-container > /dev/null

for arch in $ARCHES; do
    docker buildx build --builder arbiter-builder \
        --platform "linux/${arch}" --load \
        -t "${IMAGE}:${VERSION}-${arch}" "${SCRIPT_DIR}"
done

echo
for arch in $ARCHES; do
    echo "Built ${IMAGE}:${VERSION}-${arch}"
done
echo "Push them with: ./push-image.sh ${VERSION}"
