#/bin/bash

function usage(){
    echo "usage:  $0 -b current|patch|minor [ -t ] 
    -t (run tests)

    creates a tar archive of the system files needed to install a new ISN site 

    EXAMPLE
    to build a new minor version  (this will create a new archive in the build dir and increment the minor version in version.edn)
    $0 -b minor
    " 2>&1
	exit 1
}

while getopts "tb:" arg; do
  case $arg in
    b) export BUILD_TYPE=$OPTARG ;;
    t) export RUN_TESTS=1;;
    *) usage;;
  esac
done

if [ -z "$BUILD_TYPE" ];then
    echo '-b is required' 2>&1
    usage
fi
if [ "$BUILD_TYPE" != "current" ] && [ "$BUILD_TYPE" != "patch" ] && [ "$BUILD_TYPE" != "minor" ]; then 
    echo 'specify a build type of current, patch or minor (use patch/minor to increment version.edn) ' 2>&1
    usage
fi

dir=$(pwd)
dir=$(basename $dir)
if [ "$dir" != "isn-ref-impl" ] || [ ! -f build.clj ]; then
    echo "error: you must run this from a cloned isn-ref-impl directory" 2>&1
    exit 1
fi

if [ ! -d build ]; then
    if ! mkdir build ; then
        echo "error: could not create build dir" 2>&1
        exit 1
    fi
fi

if [ ! -f version.edn ]; then
    echo "no version.edn file found" >&2
    exit 1
fi
if [ "$RUN_TESTS" ];then
    echo running tests
    clj -X:test
fi

echo creating build for $BUILD_TYPE version
clj -T:build $BUILD_TYPE

echo creating tar file
tar czf isn.tgz config templates deps.edn README.md resources src version.edn

v=$(cat version.edn |gawk 'match($0,/.*([0-9]+.[0-9]+.[0-9]+).*/,a) { print a[1] } ')
echo $v  debug


buildfile=build/isn.${v}.tgz

mv isn.tgz $buildfile

echo $buildfile  created


