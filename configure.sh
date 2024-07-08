# todo signal def sub dir
function usage(){
    echo "usage: $0 -r root dir for site files [ -i build file ]  [ -cns ] 
    -i install the ISN software 
    -c update config.edn
    -n set up nginx site (requires root access)
    -s set up systemctl startup script (requires root access)

    EXAMPLE:
    $0 -r  /home/btd/btd-co -i build/isn.0.10.0.tgz -c" >&2
	exit 1
}
function isRoot() {
	if [ "$(whoami)" != 'root' ]; then
		return 1
	fi
}

function processSignalRepos() {
    repos=$1
    authcns='{'
    isns='#merge ['

    for repo in "${repos[@]}" ; do
        subdir=$(basename $repo|sed -e "s/.git//")

        echo "..git clone $repo $SITE_ROOT_DIR/$subdir"

        if ! git clone  $repo $SITE_ROOT_DIR/$subdir >/dev/null; then
            echo "error cloning signal definition" >&2
            exit 1
        fi
        signalfile=$(ls $SITE_ROOT_DIR/$subdir/*edn 2>/dev/null)
        if [ ! -f  "$signalfile" ]; then
            echo "error finding signal file definition in cloned repo" >&2
            exit 1
        fi
        signalname=$(gawk ' BEGIN { RS="{*[[:space:]]*:" } $0=="" { next } { print $1; exit }' < $signalfile)
        echo "..signal def file added: $signalfile (id = $signalname) " 

        isn=$(echo $signalfile |sed -e "s:$SITE_ROOT_DIR\/::")
        authcns=$(printf '%s\n  :%s #{\n    #ref [:user]\n  }' "$authcns" "$signalname")
        isns=$(printf '%s #include "%s"' "$isns" "$isn")
    done
    authcns=$(printf '%s\n }' "$authcns")
    isns=$(printf '%s ]' "$isns")
    echo "..updating authcns and isns config"
    updateConfig authcns "$authcns" < $OUTPUT_FILE > $TMP_FILE && mv $TMP_FILE $OUTPUT_FILE
    updateConfig isns "$isns" < $OUTPUT_FILE > $TMP_FILE && mv $TMP_FILE $OUTPUT_FILE
}

function getValueFromConfig() {
    config=$SITE_ROOT_DIR/config.edn

    gawk ' BEGIN {
            RS="\n[[:space:]]*:" 
        } 

            $0 ~ "^"key" "  { 
            gsub(key,"")
            gsub("\"","")
            gsub("^ *","")
            print
        }
        ' key=$1 < $config
}
function updateConfig() {
    gawk ' BEGIN {
            RS="\n[[:space:]]*:" 
        }
            $0 ~ "^"key" "  { 
                #print key"|"value"|"$0
                printf(" :%s %s\n", key, value )
                next
            }
            { if ( NF > 1 ) 
                print " :"$0 
            else
                print $0
            }
    ' key="$1" value="$2" 
}

function doInstall() {
    if [ -d "$SITE_ROOT_DIR" ];then
        echo "$SITE_ROOT_DIR already exists - assuming you want to start the installation from scratch, remove $SITE_ROOT_DIR first" 2>&1
        exit 1
    fi

    echo "..creating participant site root directory $SITE_ROOT_DIR"
    if ! mkdir $SITE_ROOT_DIR; then
        echo "could not make $SITE_ROOT_DIR" 2>&1
        exit 1
    fi

    echo "..installing ISN software"
    if ! tar xzf $BUILD_FILE --directory $SITE_ROOT_DIR; then
        echo "could not unzip $BUILD_FILE" 2>&1
        exit 1;
    fi
}

function doConfig() {
    OUTPUT_FILE=$SITE_ROOT_DIR/config.edn
    TMP_FILE=$SITE_ROOT_DIR/config.edn.tmp

    if [ -f "$OUTPUT_FILE" ]; then
        echo "error: $OUTPUT_FILE already exists.  If you want to start the config file from scratch, remove this first"
    fi

    echo "..preparing config.edn file.  Please follow prompts below "
    if ! gawk ' { gsub(";.*","");if ( $0 ~ "^[[:space:]]*$" ) next; print $0 }' < $CONFIG_TEMPLATE > $OUTPUT_FILE ; then
        echo "error could not copy $CONFIG_TEMPLATE to $OUTPUT_FILE. Did you remember to use the -i option to install the software?" >&2
        exit 1
    fi

    echo "-----------"
    echo "Enter a port for the service to run on. Make sure the port is not already in use."
    read -p "> " port
    updateConfig port "\"$port\"" < $OUTPUT_FILE > $TMP_FILE && mv $TMP_FILE $OUTPUT_FILE
    echo

    echo "-----------"
    echo "Enter a fully qualified domain name for the the Participant Site e.g. your-subdomain.your-domain.org (note this is refered to as the 'user' in the config file'"
    read -p "> " user
    updateConfig user "\"$user\"" < $OUTPUT_FILE > $TMP_FILE && mv $TMP_FILE $OUTPUT_FILE
    echo

    echo "-----------"
    echo "Enter a description for your Participant Site - this will display on the web dashboard as a title e.g. ISN Site Acme Inc"
    read -p "> " sitename
    updateConfig site-name "\"$sitename\"" < $OUTPUT_FILE > $TMP_FILE && mv $TMP_FILE $OUTPUT_FILE
    echo

    echo "-----------"
    echo "Enter the Github account name that will control the Participant Site just supply the account name (do not include https://github.com/)"
    read -p "> " gitacc
    updateConfig rel-me-github "\"https://github.com/$gitacc\"" < $OUTPUT_FILE > $TMP_FILE && mv $TMP_FILE $OUTPUT_FILE
    echo

    echo "-----------"
    echo "by default signals will be  kept in $SITE_ROOT_DIR/data/signals - Press <return> to accept the default or enter a location where the signals directory should be created. "
    read -p "> " datadir
    if [ -z "$datadir" ];then
        targetdir=$SITE_ROOT_DIR/data
    else
        targetdir=$datadir
    fi

    echo "..creating data directory $targetdir"
    if ! mkdir -p $targetdir/signals ; then
        echo "error: can't create the signals data directory: $targetdir"
        exit 1
    fi
    updateConfig data-path "\"$targetdir\"" < $OUTPUT_FILE > $TMP_FILE && mv $TMP_FILE $OUTPUT_FILE
    echo

    echo "-----------"
    echo "Enter the github repository for the ISN signal types you would like this site to process. 
    e.g  git@github.com:border-trade-demonstrators/btd-2.git

    if you would like to add more than one signal type please enter each github repository uri on separate line.

    These repos will be cloned to $SITE_ROOT_DIR (so that the service has a copy of the signal definitions) and refered to in $SITE_ROOT_DIR/config.edn
    If this account is not setup with github access type <x> below to exit the script and finish the configuration by hand"
    while true
    do
        read -p "> " repo
        if [ "$repo" = "q" ]; then
            break
        fi
        if [ "$repo" = "x" ]; then
            break
        fi
        repos+=($repo)
        echo "type 'q' to finish adding signal definitions"
    done

    if [ "$repo" = "x" ] || [ -z "$repos" ] ;then
            echo "no signals added. Exiting script - complete the rest of the installation by hand (:authcns and :isns will need upating in $SITE_ROOT_DIR/config.edn)"
            return
    fi

    echo "..adding signal definitons"
    processSignalRepos $repos
}

function doNginx() {

    port=$(getValueFromConfig port)
    user=$(getValueFromConfig user)
   
    if [ -z "$port" ] ; then
        echo "error: could not read :port value from config.edn" >&2
        exit 1
    fi
    if [ -z "$user" ] ; then
        echo "error: could not read :user value from config.edn" >&2
        exit 1
    fi

    target="/etc/nginx/sites-available/$user"
    link="/etc/nginx/sites-enabled/$user"

    if [ -f "$target" ]; then
        echo "error: there is already a nginx site configured at $target "
        exit 1
    fi

    if ! sed -e "s/port-here/$port/; s/domain-name-here/$user/" < $NGINX_TEMPLATE > $target ; then
        echo "error: could not create $target" >&2
        exit 1
    fi

    if ! ln -s $target $link ; then
        echo "error: could not create link to enable the site: $link" >&2
        exit 1
    fi

    echo site enabled at $link

    echo "do you want to restart nginx? (y/n)"
    read -p "> " answer
    if [ "$answer" = "y" ]; then
        systemctl restart nginx
    fi
    return
}

function doSystemctl() {

    user=$(getValueFromConfig user)
   
    if [ -z "$user" ] ; then
        echo "error: could not read :user value from config.edn" >&2
        exit 1
    fi

    target="/etc/systemd/system/${user}.service"

    if [ -f "$target" ]; then
        echo "error: there is already a systemctl service configured at $target "
        exit 1
    fi


    if ! sed -e "s:domain-name-here:$user:; s:site-root-dir-here:$SITE_ROOT_DIR:" < $SYSTEMCTL_TEMPLATE > $target ; then
        echo "error: could not create $target" >&2
        exit 1
    fi

    echo service config created: $target 

    shtarget=$SITE_ROOT_DIR/isn-service.sh

    if ! sed -e "s:domain-name-here:$user:g" < $ISN_SERVICE_SCRIPT_TEMPLATE  >  $shtarget; then
        echo "error: could not create $shtarget " >&2
        exit
    fi

    chmod +x $shtarget
    echo "isn-service.sh script created: $shtarget"

    echo "do you want to start the service? (y/n)"
    read -p "> " answer
    if [ "$answer" = "y" ]; then
        bash $shtarget -s 
    fi
    return
}

# main

while getopts "i:r:cns" arg; do
  case $arg in
    i) export BUILD_FILE=$OPTARG ;;
    r) export SITE_ROOT_DIR=$OPTARG ;;
    c) CONFIG=1;;
    n) NGINX=1;;
    s) SYSTEMCTL=1;;
    *) usage;;
  esac
done


export CONFIG_TEMPLATE=templates/config.template.edn
export NGINX_TEMPLATE=templates/nginx.template
export SYSTEMCTL_TEMPLATE=templates/systemctl.service.template
export ISN_SERVICE_SCRIPT_TEMPLATE=templates/isn-service-template.sh

if [ -z "$SITE_ROOT_DIR" ]; then
    echo "error: you must specify a root directory for the service "
    usage
fi

if [[ ! "$SITE_ROOT_DIR" =~ ^/.* ]];then
    SITE_ROOT_DIR=$(pwd)/$SITE_ROOT_DIR
fi

if [ "$NGINX" ] || [ "$SYSTEMCTL" ]; then
    if  ! isRoot ; then
        echo "error: script must run as root to do the nginx config" 2>&1
        exit 1
    fi
fi

if [ "$BUILD_FILE" ]; then
    if [ ! -f "$BUILD_FILE" ]; then
        echo "can't open build file $BUILD_FILE" 2>&1
        usage
    fi
    echo "--------INSTALL----------"
    doInstall
    echo "Install complete"
fi

if [ "$CONFIG" ];then
    echo "--------CONFIG----------"
    doConfig
    echo "Config complete"
fi

if [ "$NGINX" ]; then
    echo "--------NGINX----------"
    doNginx
    echo "nginx config complete"
fi
if [ "$SYSTEMCTL" ]; then
    echo "--------SYSTEMCTL----------"
    doSystemctl
    echo "systemctl config complete"
fi
echo "script complete"
