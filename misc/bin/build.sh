#!/usr/bin/env bash
# Build this project source code
#

set -xo pipefail

if [ -z "$commit" ]; then
    commit=$(git rev-parse HEAD)
else
    echo "commit set in environment"
fi

clinvar_ftp_watcher_bucket="clinvar-ftp-watcher"


################################################################
# Build the image as clinvar-fpt-watcher:SHA
cloudbuild=.cloudbuild/docker-build-dev.cloudbuild.yaml

tar --no-xattrs -c \
    Dockerfile \
    build.clj \
    deps.edn \
    misc \
    src \
    .cloudbuild \
    | gzip --fast > archive.tar.gz

gcloud builds submit \
    --substitutions="COMMIT_SHA=${commit}" \
    --config $cloudbuild \
    --gcs-log-dir=gs://$clinvar_ftp_watcher_bucket/build/logs \
    archive.tar.gz
