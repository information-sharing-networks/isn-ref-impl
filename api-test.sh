#!/bin/bash

function usage() {
  echo "usage: $0 -s server
  -p sps|labtest  (post sample signal)
  -c correlation_id (optional, use with -p.  This will create a sample signal containing a correction to a previous signal)
  -q all|query-parameters (get signals from the ISN server)

  unless running in a local dev env, you must set a BEARER_TOKEN environment variable specifying the bearer token for your site before running the script
  use '-q token' to get details of indieauth token associated with your bearer token ">&2
  exit 1
}

function isDevEnv() {
  if [ "$(uname)" = "Darwin" ]; then
    return 0
  else
    return 1
  fi
}

function getScheme() {
  if isDevEnv ; then
      echo http
  else
      echo https
  fi
}

function getETA() {
  if isDevEnv ; then
      date -u -v+2d +"%Y-%m-%dT%H:00:00Z" # mac
  else
      date -u -d "+2 days" +"%Y-%m-%dT%H:00:00"Z
  fi
}
function spsSignal() {
    u=$1
    e=$2

    cat <<!
      {
          "h": "event",
          "name": "chicken and beef (movement ref: $u)",
          "start": "$e",
          "summary": "moving to PortA with ETA $e",
          "category": [
            "pre-notification",
            "isn@sps-signal.info-sharing.network"
          ],
          "payload": {
            "cnCodes": [
              "010594",
              "020100"
            ],
            "commodityDescription": "Chicken 40%, beef 60%",
            "countryOfOrigin": "GB",
            "chedNumbers": [
              "CHEDA .GB.YYYY.XXXXXXX"
            ],
            "unitIdentification": {
              "containerNumber": "12346"
            },
            "mode": "RORO",
            "exporterEORI": "EORI-EXP-01",
            "importerEORI": "EORI-IMP-01"
          }
        }
!
}
function spsSignalCorrection() {
    u=$1
    c=$2

    cat <<!
      {
          "h": "event",
          "name": "chicken and pork (movement ref: $u)",
          "summary": "correction",
          "category": [
            "pre-notification",
            "isn@sps-signal.info-sharing.network"
          ],
          "correlation-id": "$c",
          "payload": {
            "cnCodes": [
              "010594",
              "020300"
            ],
            "commodityDescription": "Chicken 40%, pork 60%"
          }
        }
!
}
function labtestSignal() {
    u=$1
    e=$2
    cat <<!
      {
          "h": "event",
          "name": "lab sample B (lab test ref: $u)",
          "start": "$e",
          "summary": "unsatisfactory test",
          "category": [
            "lab-test-result",
            "isn@lab-test-signal.info-sharing.network"
          ],
          "payload": {
              "cnCode": "010300",
              "commodityDescription": "beef hocks",
              "storageTemperature": "ambient",
              "countryOfOrigin": "FR",
              "testDate": "2024-07-05T16:51:51.379676Z",
              "batchNumber": 134149,
              "hazardIndicator": "Aflatoxin B1",
              "acceptedHazardLevel": 2,
              "testResult": 3,
              "testOutcome": "unsatisfactory",
              "countryofLab": "FR"
            }
        }
!
}
function labtestSignalCorrection() {
    u=$1
    c="$2"
    cat <<!
      {
          "h": "event",
          "name": "lab sample B (lab test ref: $u)",
          "summary": "satisfactory test (correction)",
          "category": [
            "lab-test",
            "isn@lab-test-signal.info-sharing.network"
          ],
          "correlation-id": "$c",
          "payload": {
              "hazardIndicator": "none",
              "testOutcome": "satisfactory"
            }
        }
!
}

# main

while getopts "s:p:q:c:" arg; do
    case $arg in
        s) server=$(getScheme)://$OPTARG;;
        q) query=0 ; query_parameters=$OPTARG;;
        p) post=0 ; post_type=$OPTARG;;
        c) correlation_id=$OPTARG;;
        *)  usage;;
    esac
done


if [ -z "$server" ]; then
    echo "specify a server" >&2
    usage
fi

if [ "$correlation_id" ] && [ -z "$post" ]; then
  echo "error: the -c correlation id option cam only be used with -p" >&2
  usage
fi

if [ -z "$post" ] && [ -z "$query" ]; then
    echo "error: you must specify -q or -p " >&2
    usage
fi

if [ "$query" ] && [ "$post" ]; then
    echo "error: -p and -q can't be used together" >&2
    usage
fi

if ! isDevEnv ; then
  if [ -z "$BEARER_TOKEN" ]; then
      echo "set BEARER_TOKEN variable" >&2
      exit 1;
  fi
fi

if [ "$post" ] ; then
    id=$(LC_ALL=C tr -dc A-Z0-9 </dev/urandom | head -c 4)
    eta=$(getETA)
    echo "transaction ref: $id"

    case $post_type in
      sps)
        if [ "$correlation_id" ]; then
          curl --silent -i -X POST \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $BEARER_TOKEN" \
            -d "$(spsSignalCorrection $id $correlation_id)" \
            $server/micropub |egrep "HTTP|^\"|^Location" 
        else
          curl --silent -i -X POST \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $BEARER_TOKEN" \
            -d "$(spsSignal $id $eta)" \
            $server/micropub |egrep "HTTP|^\"|^Location" 
        fi ;;
    labtest)
        if [ "$correlation_id" ]; then
          curl --silent -i -X POST \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $BEARER_TOKEN" \
            -d "$(labtestSignalCorrection $id $correlation_id)" \
            $server/micropub |egrep "HTTP|^\"|^Location" 
        else
          curl --silent -i -X POST \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $BEARER_TOKEN" \
            -d "$(labtestSignal $id $eta)" \
            $server/micropub |egrep "HTTP|^\"|^Location" 
        fi ;;
    *)
        echo unknown post option  >&2; usage ;;
    esac
fi

if [ "$query" ]; then

    case $query_parameters in
    all) 
          curl --silent -H "Authorization: Bearer $BEARER_TOKEN"  "$server/signals";;
    *=*)
          curl --silent -H "Authorization: Bearer $BEARER_TOKEN"  "$server/signals?$query_parameters";;
     token)
            curl --silent -H "Accept: application/json" \
                -H "Authorization: Bearer $BEARER_TOKEN" \
                "https://tokens.indieauth.com/token";;
      *)
          echo "invalid query parameters supplied (should be "all" or, for example, countryOfOrigin=FR ) " >&2; usage ;;
    esac
fi
