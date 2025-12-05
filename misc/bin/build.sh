#!/usr/bin/env bash
# Build this project source code
#

set -xo pipefail

if [ -z "$commit" ]; then
    commit=$(git rev-parse HEAD)
else
    echo "commit set in environment"
fi

echo "Branch: $branch"
echo "Commit: $commit"

set -ue

clinvar_ftp_watcher_bucket="clinvar-ftp-watcher"
region="us-east1"
project=$(gcloud config get project)
image=gcr.io/clingen-dev/clinvar-ftp-watcher:$commit


################################################################
# Build the image
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
    --config .cloudbuild/docker-build-dev.cloudbuild.yaml \
    --gcs-log-dir=gs://$clinvar_ftp_watcher_bucket/build/logs \
    archive.tar.gz
