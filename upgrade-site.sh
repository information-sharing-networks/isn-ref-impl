# install specified version of software on a site installed locally

function usage() {
  echo "usage:  $0 -b build file -r site root directory
    you can use use 'build.sh -b current' to create the latest build file if none are available.
    " 2>&1
    exit 1
}

while getopts "b:r:" arg; do
  case $arg in
    b) export BUILD_FILE=$OPTARG ;;
    r) export SITE_ROOT_DIR=$OPTARG ;;
    *) usage;;
  esac
done

if [ -z "$BUILD_FILE" ] || [ -z "$SITE_ROOT_DIR" ] ; then
    echo "error: you must specify values for both -b and -r" >&2
    usage
fi

if [ ! -f "$BUILD_FILE" ]; then
    echo "error: could not open build file:  $BUILD_FILE" >&2 
    exit 1
fi

if [ ! -d "$SITE_ROOT_DIR" ]; then
    echo "error: could not open site directory: $SITE_ROOT_DIR" >&2
    exit
fi

if [ ! -f "$SITE_ROOT_DIR/config.edn" ]; then
    echo "error: could not open $SITE_ROOT_DIR/config.edn - invalid root directory?" >&2
    exit
fi

echo "installing $BUILD_FILE to $SITE_ROOT_DIR"
if ! tar xzf $BUILD_FILE --directory $SITE_ROOT_DIR; then
    echo "error could not untar $BUILD_FILE to $SITE_ROOT_DIR" >&2
    exit 1
fi
echo "site upgraded. Don't forget to stop and start the service usng isn-service.sh" 
