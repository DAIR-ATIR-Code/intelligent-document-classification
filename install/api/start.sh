#!/bin/bash

#####################################################################
#
# This script Starts the Intelligent Document Classification API
#
#####################################################################

cd ~/intelligent-document-classification
docker-compose -f docker-compose-prod.yml up -d