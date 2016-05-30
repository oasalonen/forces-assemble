#!/bin/bash

if [ "$1" = "local" ]; then
	DOMAIN=localhost:8443
else
	DOMAIN=forces-assemble.herokuapp.com
fi

SERVER_URL="https://${DOMAIN}/channels/ch1/events"
EVENT="{\"title\":\"Debug event\", \"body\":\"curl says hello\", \"malicious\":\"boo!\", \"data\": {\"foo\": \"bar\"}}"
echo "Event:" $EVENT
echo "Sending event to:" $SERVER_URL
set -v
curl -X POST -H 'Content-Type: application/json' -d "${EVENT}" $SERVER_URL -i -k
