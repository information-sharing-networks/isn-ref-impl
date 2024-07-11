#!/bin/bash
function usage() {
  echo "usage: $0 -r site root directory [ -srv ]
      control the domain-name-here service 

      -s start the service
      -r stop the service
      -v service status " >&2
  exit 1
}
function isRoot() {
  if [ "$(whoami)" != 'root' ]; then
      return 1
  fi
}
if ! isRoot ; then
  echo "error: this script must be run as root" >&2
  exit 1
fi

while getopts "svr" arg; do
    case $arg in
      s) START=1;;
      r) STOP=1;;
      v) STATUS=1;;
      *) usage;;
    esac
done

if [ -z "$STOP" ] && [ -z "$STATUS" ] && [ -z "$START" ]; then
    usage 
fi

domainname=domain-name-here

if [ -z "$domainname" ];then
    echo "error: could not read :user from $SITE_ROOT_DIR" >&2
    exit 1
fi

service=${domainname}.service
servicefile=/etc/systemd/system/$service

if [ ! -f "$servicefile" ];then
    echo "error: can't open service file: $servicefile" 2>&1
    exit 1
fi

if [ "$STOP" ]; then 
    echo stopping $service
    systemctl stop $service
    exit
fi

if [ "$STATUS" ]; then 
    echo $service status
    systemctl status $service
    exit
fi

if [ "$START" ]; then 
    echo starting service
    systemctl start $service
fi
