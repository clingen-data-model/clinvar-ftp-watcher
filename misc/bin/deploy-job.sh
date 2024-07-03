#!/usr/bin/env bash
# Build and deploy this project source code as a GCP cloud run job.
#
# Usage: shell> instance="clinvar-vcv-ftp-watcher" sh ./misc/bin//deploy-job.sh
#    or: shell> instance="clinvar-rcv-ftp-watcher-xxx" sh ./misc/bin/deploy-job.sh           

set -xo pipefail

if [ -z "$instance" ]; then
    echo "'instance' must set in environment. It defines the name of the cloud run job deployment."
    echo "It should have either 'rcv' or 'vcv' in the name."
    exit -1
fi

echo "${instance}" | egrep -i "vcv|rcv" > /dev/null
if [ $? -ne 0 ]; then
    echo "'instance' must have 'rcv' or 'vcv' in the name."
    exit -1
fi

if [ -z "$commit" ]; then
    commit=$(git rev-parse HEAD)
else
    echo "commit set in environment"
fi

echo "Branch: $branch"
echo "Commit: $commit"
echo "Instance name: $instance"

set -ue

clinvar_ftp_watcher_bucket="clinvar-ftp-watcher"
region="us-east1"
project=$(gcloud config get project)
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
    --gcs-log-dir=gs://$clinvar_ftp_watcher_bucket/build/logs \
    archive.tar.gz

################################################################
# Deploy job
if gcloud run jobs list --region $region | awk '{print $2}' | grep "^${instance}$"  ; then
    echo "Cloud Run Job $instance already exists - updating it"
    command="update"
else
    echo "Cloud Run Job $instance doesn't exist - creating it"
    command="create"
fi

# clinvar_ftp_watcher env defaults are variant biased
vcv_cloud_run_deploy="gcloud run jobs ${command} ${instance} \
    --cpu=1 \
    --image=$image \
    --max-retries=0 \
    --region=${region} \
    --service-account=${deployment_service_account} \
    --set-secrets=DX_JAAS_CONFIG=dx-prod-jaas:latest"

# override variant biased env vars with rcv specifics
rcv_cloud_run_deploy="${vcv_cloud_run_deploy} \
    --set-env-vars=CLINVAR_FTP_WATCHER_TOPIC=clinvar-rcv-ftp-watcher \
    --set-env-vars=NCBI_CLINVAR_WEEKLY_FTP_DIR=/pub/clinvar/xml/RCV_release/weekly_release \
    --set-env-vars=NCBI_CLINVAR_FILE_NAME_BASE=ClinVarRCVRelease \
    --set-env-vars=GCP_WORKFLOW_LOCATION=${region} \
    --set-env-vars=GCP_WORKFLOW_NAME=clinvar-rcv-ingest"

set +e

echo "${instance}" | grep -i "rcv" > /dev/null
if [ $? -eq 0 ]; then
    echo "Running the RCV watcher deployment..."
    $rcv_cloud_run_deploy
    echo "Running RCV cloud run scheduler deployment"
    # --schedule causes problems unless run as subshell
    # $(gcloud scheduler jobs ${command} http clinvar-rcv-ftp-watcher-scheduler --location ${region} --schedule='50 * * * *' --uri=https://${region}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${project}/jobs/clinvar-rcv-ftp-watcher:run --http-method POST --oauth-service-account-email=cloudrun@${project}.iam.gserviceaccount.com)
else
    echo "Running the VCV watcher deployment..."
    $vcv_cloud_run_deploy
    echo "Running VCV cloud run scheduler deployment"
    # --schedule causes problems unless run as subshell
    # $(gcloud scheduler jobs ${command} http clinvar-vcv-ftp-watcher-scheduler --location ${region} --schedule='45 * * * *' --uri=https://${region}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${project}/jobs/clinvar-vcv-ftp-watcher:run --http-method POST --oauth-service-account-email=cloudrun@${project}.iam.gserviceaccount.com)
fi
echo "Deployment complete."
