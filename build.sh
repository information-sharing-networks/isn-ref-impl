#/bin/bash

clj -X:test
clj -T:build build
tar cvzf isn.tgz config config.template.edn deps.edn README.md resources scripts src START.template STOP.template version.edn
