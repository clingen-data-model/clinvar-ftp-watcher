#!/usr/bin/env bash

set -xeo pipefail

if [ -z "$branch" ]; then
    branch=$(git rev-parse --abbrev-ref HEAD)
else
    echo "branch set in environment"
fi
if [ -z "$commit" ]; then
    commit=$(git rev-parse HEAD)
else
    echo "commit set in environment"
fi

echo "Branch: $branch"
echo "Commit: $commit"

set -u

instance_name="clinvar-ftp-watcher-${branch}"
clinvar_ftp_watcher_bucket="clinvar-ftp-watcher"

region="us-central1"
# project=$(gcloud config get project)
image_tag=workflow-py-$commit
image=gcr.io/clingen-dev/clinvar-ftp-watcher:$image_tag
deployment_service_account=clinvar-ftp-watcher-deployment@clingen-dev.iam.gserviceaccount.com


if gcloud run jobs list --region us-central1 | awk '{print $2}' | grep "^$instance_name$"  ; then
    echo "Cloud Run Job $instance_name already exists"
    echo "Deleting Cloud Run Job"
    gcloud run jobs delete $instance_name --region $region --quiet
fi

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
    --substitutions="COMMIT_SHA=${image_tag}" \
    --config .cloudbuild/docker-build-dev.cloudbuild.yaml \
    --gcs-log-dir=gs://${clinvar_ftp_watcher_bucket}/build/logs \
    archive.tar.gz

################################################################
# Deploy job

gcloud run jobs create $instance_name \
    --image=$image \
    --region=$region \
    --service-account=$pipeline_service_account \
    --set-env-vars=CLINVAR_FTP_WATCHER_BUCKET=$clinvar_ingest_bucket
