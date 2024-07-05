function usage() {
  echo "usage: $0 -s server
  -p btd1|btd2  (post sample signal)
  -g all (get signals)" >&2
  exit 1
}
function getAllSignals() {
    curl --silent -H "Authorization: Bearer $BEARER_TOKEN"  $server/signals
}
function btd1Signal() {
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
            "isn@btd-1.info-sharing.network"
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
              "ContainerNumber": "12346"
            },
            "mode": "RORO",
            "exporterEORI": "EORI-EXP-01",
            "importerEORI": "EORI-IMP-01"
          }
        }
!
}
function btd2Signal() {
    u=$1
    e=$2
    cat <<!
      {
          "h": "event",
          "name": "lab sample B",
          "start": "$e",
          "summary": "tested unsatisfactory at LabB (lab test ref: $u)",
          "category": [
            "lab-sample-industry-unsatisfactory",
            "isn@btd-2.info-sharing.network"
          ],
          "payload": {
              "authorisedPesticides": true,
              "acceptedHazardLevel": 2,
              "testResult": 3,
              "hazard": "Aflatoxin",
              "cnCode": "a-cn-code",
              "reasonForSample": "quality-control",
              "testDate": "2024-01-05T16:51:51.379676Z",
              "labType": "Official",
              "countryOfOrigin": "GB",
              "batchNumber": 134149,
              "testOutcome": "unsatisfactory",
              "countryofLab": "GB",
              "storageTemperature": "ambient",
              "commodityDescription": "A description",
              "hazardIndicator": "Aflatoxin B1"
            }
        }
!
}

# main
if [ -z "$BEARER_TOKEN" ]; then
    echo "set BEARER_TOKEN variable" >&2
    exit 1;
fi

while getopts "s:p:g:" arg; do
    case $arg in
        s) server=https://$OPTARG;;
        p) post_type=$OPTARG;;
        g) get_type=$OPTARG;;
        *)  usage;;
    esac
done

if [ -z "$server" ]; then
    echo "specify a server" >&2
    usage
fi

if [ -z "$post_type" ] && [ -z "$get_type" ]; then
    echo "error: you must specify -g or -p " >&2
    usage
fi


if [ "$post_type" ]; then
    id=$(tr -dc A-Z0-9 </dev/urandom | head -c 4)
    eta=$(date -u -d "+2 days" +"%Y-%m-%dT%H:00:00"Z)
    echo "transaction ref: $id"

    case $post_type in
    btd1) 
        curl --silent -i -X POST -H "Content-Type: application/json" -H "Authorization: Bearer $BEARER_TOKEN"  -d "$(btd1Signal $id $eta)" $server/micropub |grep HTTP;;
    btd2)
        curl --silent -i -X POST -H "Content-Type: application/json" -H "Authorization: Bearer $BEARER_TOKEN"  -d "$(btd2Signal $id $eta)" $server/micropub |grep HTTP;;
    *) echo unknown post option  >&2; usage ;;
    esac
fi

# todo more GET tests
if [ "$get_type" ]; then

    case $get_type in
    all) 
          getAllSignals ;;
    *) echo unknown post option  >&2; usage ;;
    esac
fi
