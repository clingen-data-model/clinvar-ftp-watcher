# clinvar-ftp-watcher
Code for detecting new files on the ClinVar FTP site. 

It can be configured to look for new dated files of the form 'filename\_YYYY-MMDD.xml.gz'. By default, will look for the file 'ClinVarVariationRelease\_YYYY-MMDD.xml.gz'.

It works as follows - all values in this description are default configured values:
- read the last offset from the 'clinvar\_vcv\_ftp\watcher' containing records of dated files
- retrieve the date of the last file recorded from that offset (there may be multiple dated files in a single recorded entry) 
- use that date to compare against all of the 'ClinVarVariationRelease\_YYYY-MMDD.xml.gz' dated files in https://ftp.ncbi.nlm.nih.gov/pub/clinvar/xml/clinvar_variation/weekly_release/
- when there are more recent files, store them in a new message in the configured cloud topic.
- initiate the 'clinvar\_vcv\_ingest' cloud run job in google cloud.

The 'weekly\_release' directory contains the following files:
- 'ClinVarVariationRelease\_00-latest\_weekly.xml.gz' - file that is a symbolic link to a dated release file. At the beginning of the month, ClinVar moves all of the dated files for the previous month to the parent clinvar\_variation directory. As new weekly dated files are added to the weekly_release directory throught any given month, this file will be symbolically linked to the latest weekly release dated file.
- md5 checksum files - for every .gz file in this directory there is a checksum file in the form .gz.md5
- ClinVarVariationRelease\_YYYY-MMDD.xml.gz - these are the dated files that this process reports on.

This code has been deployed as a cloud run job named 'clinvar-vcv-ftp-watcher' and an associated cloud scheduler trigger scheduled to run every hour.

A new build requires an edit to the cloud run job to update the container image.
- Go to cloud run page - https://console.cloud.google.com/run/jobs?orgonly=true&project=clingen-dev&supportedpurview=organizationId
Build and deploy scripts can be found in the misc/bin directory.

Environment Variables:

"DX\_JAAS\_CONFIG" must be defined, this is the kafka permission string

"CLINVAR\_FTP\_WATCHER\_TOPIC" Kafka topic to read/write, defaults to 'clinvar-vcv-ftp-watcher' when not explicitly defined

"NCBI\_CLINVAR\_WEEKLY\_FTP\_DIR" defaults to "/pub/clinvar/xml/clinvar\_variation/weekly\_release" when not explicitly defined
"NCBI\_CLINVAR\_FTP\_SITE" defaults to "https://ftp.ncbi.nlm.nih.gov" when not explicitly defined
"NCBI\_CLINVAR\_FILE\_NAME\_BASE" - the base file name to look for - defaults to 'ClinVarVariationRelease' when not explicitly defined

"GCP\_WORKFLOW\_PROJECT\_ID" this is the GCP Project ID where the workflow resides, defaults to "clingen-dev" when not explicitly defined
"GCP\_WORKFLOW\_LOCATION" this is the region where the workflow resides, such as "us-central1" when not explicitly defined
"GCP\_WORKFLOW\_NAME" this is the name of the workflow to invoke, such as "clinvar-ingest" when not explicitly defined

Command Line Arguments:
--kafka = do not write the release information to the kafka topic.
--workflow = do not make calls to initiate the workflow processing of the clinvar release.
