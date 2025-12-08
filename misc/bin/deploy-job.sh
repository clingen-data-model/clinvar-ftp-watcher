#!/usr/bin/env bash
# Deploy the clinvar-ftp-watcher image (assumes this was built in a previous step with build.sh)
# and project env as a GCP cloud run job.
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

echo "Commit: $commit"
echo "Instance name: $instance"

set -ue

region="us-east1"
project=$(gcloud config get project)
image=gcr.io/clingen-dev/clinvar-ftp-watcher:$commit
deployment_service_account=clinvar-ftp-watcher-deployment@clingen-dev.iam.gserviceaccount.com


################################################################
# Deploy job
if gcloud run jobs list --region $region | awk '{print $2}' | grep "^${instance}$"  ; then
    echo "Cloud Run Job $instance already exists - updating it"
    command="update"
else
    echo "Cloud Run Job $instance doesn't exist - creating it"
    command="create"
fi

cloud_run_cmd_common="gcloud run jobs ${command} ${instance} \
    --cpu=1 \
    --image=$image \
    --max-retries=0 \
    --region=${region} \
    --service-account=${deployment_service_account} \
    --set-secrets=DX_JAAS_CONFIG=dx-prod-jaas:latest \
    --set-secrets=CLINVAR_FTP_WATCHER_SLACK_BOT_TOKEN=clinvar-ingest-slack-token:latest \
    --set-env-vars=CLINVAR_FTP_WATCHER_SLACK_CHANNEL=C06QFR0278D"

## VCV (old)
# clinvar_ftp_watcher env defaults are variant biased
vcv_cloud_run_deploy="${cloud_run_cmd_common} \
    --set-env-vars=GCP_WORKFLOW_NAME=clinvar-ingest-copy-only-v1"

## RCV (current)
# override variant biased env vars with rcv specifics
rcv_cloud_run_deploy="${cloud_run_cmd_common} \
    --set-env-vars=CLINVAR_FTP_WATCHER_TOPIC=clinvar-rcv-ftp-watcher \
    --set-env-vars=NCBI_CLINVAR_WEEKLY_FTP_DIR=/pub/clinvar/xml/RCV_release/weekly_release \
    --set-env-vars=NCBI_CLINVAR_FILE_NAME_BASE=ClinVarRCVRelease \
    --set-env-vars=GCP_WORKFLOW_LOCATION=${region} \
    --set-env-vars=GCP_WORKFLOW_NAME=clinvar-rcv-ingest"

## VCV (current)
# override variant biased env vars with somatic specifics
vcv_new_cloud_run_deploy="${cloud_run_cmd_common} \
    --set-env-vars=CLINVAR_FTP_WATCHER_TOPIC=clinvar-somatic-ftp-watcher \
    --set-env-vars=NCBI_CLINVAR_WEEKLY_FTP_DIR=/pub/clinvar/xml/weekly_release \
    --set-env-vars=NCBI_CLINVAR_FILE_NAME_BASE=ClinVarVCVRelease \
    --set-env-vars=GCP_WORKFLOW_LOCATION=${region} \
    --set-env-vars=GCP_WORKFLOW_NAME=clinvar-vcv-ingest"

scheduler_command="gcloud scheduler jobs ${command} http ${instance} \
    --location ${region} \
    --uri=https://${region}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${project}/jobs/${instance}:run \
    --http-method POST \
    --oauth-service-account-email=clinvar-ftp-watcher-deployment@clingen-dev.iam.gserviceaccount.com"

# turn on echo turn off filename expansion of wildcards
set +e -f

if [ ${instance} == "clinvar-rcv-ftp-watcher" ]; then
    echo "Running the RCV watcher deployment..."
    $rcv_cloud_run_deploy
    echo "Running RCV cloud run scheduler deployment"
    $scheduler_command --schedule='50 * * * *'
elif [ ${instance} == "clinvar-vcv-ftp-watcher" ]; then
    echo "Running the VCV watcher deployment..."
    $vcv_cloud_run_deploy
    echo "Running VCV cloud run scheduler deployment"
    $scheduler_command --schedule='45 * * * *'
elif [ ${instance} == "clinvar-vcv-ftp-watcher-new" ]; then
    echo "Running the VCV watcher deployment..."
    $vcv_new_cloud_run_deploy
    echo "Running VCV NEW cloud run scheduler deployment"
    $scheduler_command --schedule='55 * * * *'
fi
echo "Deployment complete."
