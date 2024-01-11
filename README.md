# clinvar-ftp-watcher
Code for detecting new files on the clinvar ftp site. It works as follows:
- read the last offset from the clinvar_ftp_watcher cloud topic containing dated files
- retrieve the date of the last file recorded from that offset (there may be multiple dated files in a single recorded entry) 
- use that date to compare against all of the dated files in https://ftp.ncbi.nlm.nih.gov/pub/clinvar/xml/clinvar_variation/weekly_release/
- when there are more recent files, store them in a new message in clinvar_ftp_watcher topic.
- initiate the clinvar-ingest workflow in google cloud.

The above references to "dated files" are file names in the form ClinVarVariationRelease_YYYY-MMDD.xml.gz. These are the only files currently processed by this code. The weekly_release directory contains the following files:
- ClinVarVariationRelease_00-latest_weekly.xml.gz - file that is a symbolic link to a dated release file. At the beginning of the month, ClinVar moves all of the dated files for the previous month to the parent clinvar_variation directory. As new weekly dated files are added to the weekly_release directory throught any given month, this file will be symbolically linked to the latest weekly release dated file.
- md5 checksum files - for every .gz file in this directory there is a checksum file in the form .gz.md5
- ClinVarVariationRelease_YYYY-MMDD.xml.gz - these are the dated files that this process reports on.

This code has been deployed as a cloud run job named clinvar-ftp-watcher and an associated cloud scheduler trigger scheduled to run every hour.

For now, cloud builds can be invoked from the local source directory via:
gcloud builds submit --project=clingen-dev --config ./.cloudbuild/docker-build-dev.cloudbuild.yaml --substitutions=COMMIT_SHA="testbuild" .

A new build requires an edit to the cloud run job to update the container image.
- Go to cloud run page - https://console.cloud.google.com/run/jobs?orgonly=true&project=clingen-dev&supportedpurview=organizationId
- click on clinvar-ftp-watcher
- on the job details page click "Edit"
- change the "Container image url" by clicking "Select"
- then select "Container registry"
- open the gcr.io/clingen-dev/clinvar-ftp-watcher thingy
- then click "select" on the latest build
- click "update" on the cloud run job edit page

Environment Variables:

"DX_JAAS_CONFIG" must be defined, this is the kafka permission string

"CLINVAR_FTP_WATCHER_TOPIC" Kafka topic to read/write, defaults to 'clinvar_ftp_watcher' when not explicitly defined

"NCBI_CLINVAR_WEEKLY_FTP_DIR" defaults to "/pub/clinvar/xml/clinvar_variation/weekly_release" when not explicitly defined
"NCBI_CLINVAR_FTP_SITE" defaults to "https://ftp.ncbi.nlm.nih.gov" when not explicitly defined

"GCP_WORKFLOW_PROJECT_ID" this is the GCP Project ID where the workflow resides, defaults to "clingen-dev" when not explicitly defined
"GCP_WORKFLOW_LOCATION" this is the region where the workflow resides, such as "us-central1" when not explicitly defined
"GCP_WORKFLOW_NAME" this is the name of the workflow to invoke, such as "clinvar-ingest" when not explicitly defined

Command Line Arguments:
--kafka = do not write the release information to the kafka topic.
--workflow = do not make calls to initiate the workflow processing of the clinvar release.
