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

if [ "$branch" == "main" ]; then
    instance_name="clinvar-ftp-watcher"
else
    instance_name="clinvar-ftp-watcher-${branch}"
fi
clinvar_ftp_watcher_bucket="clinvar-ftp-watcher"

region="us-central1"
# project=$(gcloud config get project)
image=gcr.io/clingen-dev/clinvar-ftp-watcher:$commit
deployment_service_account=clinvar-ftp-watcher-deployment@clingen-dev.iam.gserviceaccount.com


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
    --gcs-log-dir=gs://${clinvar_ftp_watcher_bucket}/build/logs \
    archive.tar.gz

################################################################
# Deploy job
if gcloud run jobs list --region us-central1 | awk '{print $2}' | grep "^$instance_name$"  ; then
    echo "Cloud Run Job $instance_name already exists - updating it"
    command="update"
else
    echo "Cloud Run Job $instance_name doesn't exist - creating it"
    command="create"
fi
gcloud run jobs $command $instance_name \
    --cpu=1 \
    --image=$image \
    --max-retries=0 \
    --region=$region \
    --service-account=$deployment_service_account \
    --set-secrets=DX_JAAS_CONFIG=dx-prod-jaas:latest


