#/bin/bash

if [[ $# -eq 0 ]] ; then
    echo 'specify either patch or minor to build version e.g. ./deploy.sh patch'
    exit 0
fi

clj -X:test
clj -T:build $1
tar cvzf isn.tgz config config.template.edn deps.edn README.md resources scripts src START.template STOP.template version.edn
