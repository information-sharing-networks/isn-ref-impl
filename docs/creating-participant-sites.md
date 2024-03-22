# Creating participant sites

## Creating participant sites in an existing ISN

### Create code and config directory on machine where ISN is hosted

#### Step 1 (remote machine)

Use ssh to navigate to remote machine.
```bash
ssh ISN-MACHINE
```

Create a directory for the code and config for the Participant Site (PS).
```bash
mkdir isn-x-abc
```

#### Step 2 (local dev machine)

Navigate to project directory on your file system in terminal.
```bash
cd path/to/isn-ref-impl
```

Run the deployment script (note minor is the minor component of the version number). Which will:
- run the tests (check if they succeed)
- cretae the archive
- compress the archive
- transfer the compressed archive to the remote machine (this is configured in the deploy.sh script
```bash
./deploy.sh minor
```

ssh to remote machine.
```bash
ssh ISN-MACHINE
```

Copy the deployed archive into the directory you created in Step 1.
```bash
cp isn.tgz isn-x-abc
```

Move into new isn directory and unpack isn artefact.
```bash
cd isn-x-abc
gunzip isn.tgz
tar xf isn.tar
```

#### Step 3 (remote machine)

Create config.edn from config.template.edn.
```bash
cp config.template.edn config.edn
```

Edit config.edn for your new PS.

1. Configure port to a value not in use
2. Provide a useful value for 'site-name'
3. Set use to a fully qualified domain name that someone in the ISN controls e.g. acme-signals.my-exmple.xyz
4. Set 'rel-me-github' to point to a Github profile that some party in the ISN controls
5. Configure 'authcns' by adding the existing ISN you want to join (fully qualified domain name) and then ensure that user is included under that ISN domain name
6. Uncomment the 'isns' include and merge line and set to the correct BTD
7. Set the 'data-path' to follow the convention of home directory followed by '.isn' e.g. /root/.isn/isn-x/isn-site-abc/data

#### Step 4 (remote machine)

Clone the Github repository for the ISN that you want to join in the new isn directory.

Meta example:
```bash
git clone git@github.com:your-organisation/your-isn-repository.git the-directory-in-the-isns-include
```

Previous workng example
```bash
git clone git@github.com:border-trade-demonstrators/btd-2.git isn-btd-2
```

You will need to do this for each ISN you with the new PS to participate in. You may need to provide a passphrase for the SSH key if it is not added to the ssh agent.

Create the data-path location on the file system.

Still in the new ISN PS directory

Meta example:
```bash
mkdir -p /root/.isn/isn-x/isn-site-abc/data/signals
```
