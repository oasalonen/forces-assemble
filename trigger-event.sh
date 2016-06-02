#!/bin/bash

if [ "$2" = "local" ]; then
	DOMAIN=localhost:8443
else
	DOMAIN=forces-assemble.herokuapp.com
fi

AUTH_TOKEN=$1
SERVER_URL="https://${DOMAIN}/api/v1/channels/ch1/events"
EVENT="{\"title\":\"Debug event\", \"body\":\"curl says hello\", \"malicious\":\"boo!\", \"data\": {\"foo\": \"bar\"}}"
echo "Auth token:" $AUTH_TOKEN
echo "Event:" $EVENT
echo "Sending event to:" $SERVER_URL
set -v
curl -X POST -H 'Content-Type: application/json' -H "Authorization:${AUTH_TOKEN}" -d "${EVENT}" $SERVER_URL -i -k
