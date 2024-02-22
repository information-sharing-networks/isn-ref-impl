#/bin/bash

if [[ $# -eq 0 ]] ; then
    echo 'specify either patch or minor to build version e.g. ./deploy.sh patch'
    exit 0
fi

./build.sh $1
scp isn.tgz  your-server-details
