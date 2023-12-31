#!/bin/bash

#####################################################################
#
# This script Installs the Intelligent Document Classification API
#
#####################################################################

yum -y update

yum -y install docker git

curl -L https://github.com/docker/compose/releases/download/1.22.0/docker-compose-$(uname -s)-$(uname -m) -o /usr/bin/docker-compose

chmod +x /usr/bin/docker-compose

rm -r -f ~/intelligent-document-classification
git clone https://github.com/formkiq/intelligent-document-classification.git ~/intelligent-document-classification
cd ~/intelligent-document-classification
git checkout --track origin/v1

echo "Enable Docker Service"
systemctl enable docker.service

echo "Starting Docker Service"
systemctl start docker.service

rm -r -f /etc/letsencrypt/
path="/etc/letsencrypt/live/app.${IP_PUBLIC}.nip.io"
mkdir -p "$path"

echo "Generating Self Signed Certificate"
openssl req -x509 -nodes -newkey rsa:4096 -days 1000 -keyout "${path}/privkey.pem" -out "${path}/fullchain.pem" -subj "/CN=localhost"

echo "Building Docker Project"
docker-compose -f docker-compose-prod.yml build --build-arg SERVER_NAME="app.${IP_PUBLIC}.nip.io"

echo "Launching Docker Project"
docker-compose -f docker-compose-prod.yml up -d

echo "Launching Docker PS"
docker ps

echo "Generating Lets Encrypt Certificate"
rm -r -f /etc/letsencrypt/
docker run --rm -v /var/www/certbot/:/var/www/certbot/ -v /etc/letsencrypt/:/etc/letsencrypt/ certbot/certbot certonly --webroot --register-unsafely-without-email --agree-tos --webroot-path=/var/www/certbot/ -d "app.${IP_PUBLIC}.nip.io" > /tmp/letsencrypt.txt 2> /tmp/letsencrypt_err.txt

if [ ! -f "${path}/fullchain.pem" ]; then
  echo "Defaulting to Self-signed certificate"
  rm -r -f /etc/letsencrypt/
  mkdir -p "$path"
  openssl req -x509 -nodes -newkey rsa:4096 -days 1000 -keyout "${path}/privkey.pem" -out "${path}/fullchain.pem" -subj "/CN=localhost"
fi

cd ~/intelligent-document-classification
docker-compose -f docker-compose-prod.yml down 

echo "Launching Intelligent Document Classification Application"
docker-compose -f docker-compose-prod.yml up -d