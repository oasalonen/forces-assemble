#!/bin/bash

if [ "$1" = "local" ]; then
	DOMAIN=localhost:8443
else
	DOMAIN=forces-assemble.herokuapp.com
fi

SERVER_URL="https://${DOMAIN}/channels/ch1/events"
echo "Sending event to:" $SERVER_URL
set -v
curl -X POST -H 'Content-Type: application/json' -d '{"title":"Debug event", "body":"curl says hello"}' $SERVER_URL -i -k
