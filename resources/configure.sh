echo "This script will configure your new Participant Site within an existing ISN. Please follow the prompts supplying values to each as the script indicates"
echo "Enter directory name"
read -p ">" dirname

if [ ! -d "$dirname" ]
then
    echo "..Particpant Site directory doesn't exist, creating now"
    mkdir ./$dirname
    echo "..Participant Site directory created"
    cp isn.tgz $dirname

    cd $dirname

    gunzip isn.tgz
    tar xf isn.tar
    rm isn.tar
    echo "..Code artefact uncompressed and unpacked"

    cp resources/config.sample.edn config.edn
    echo "..Sample config.edn copied into place"
    echo

    echo "Enter a port for the service to run on one scheme is for a 1st isn 5001 - 5010, 2nd isn 5011 - 5020, make sure the port is not in use or this PS won't start"
    read -p ">" port
    sed -i "2 i :port $port" config.edn
    echo

    echo "Enter a name for your Participant Site - this will display on the web dashboard as a title e.g. ISN Site Acme Inc"
    read -p ">" sitename
    sed -i "3 i :site-name \"$sitename\"" config.edn
    echo

    echo "Enter a fully qualified host for the user who controls this Participant Site e.g. acme-actor.your-domain.xyz"
    read -p ">" user
    sed -i "5 i :user \"$user\"" config.edn
    echo

    echo "Enter Github account name (this will control the Participant Site) e.g. MyAccount11"
    read -p ">" gitacc
    sed -i "8 i :rel-me-github \"https://github.com/$gitacc\"" config.edn
    echo

    echo "Enter an ISN you would like this Participant Site to participate in e.g. the-isn.info-sharing.network"
    read -p ">" isn
    sed -i "10 i $isn #{ #ref [:user] }" config.edn

    sed -i "21 i :data-path \"$HOME/.isn/$dirname/data\"" config.edn
    mkdir -p $HOME/.isn/$dirname/data/signals
    echo "..data-path set to $HOME/.isn/$dirname/data in config.edn"
    echo "..data-path created on filesystem"

    echo "..config.edn populated per your inputs"
    echo 
    echo "Please continue following the deployment instructions in the isn Github repository"
else
    echo "Cannot create this Participant Site directory, it already exists"
fi



