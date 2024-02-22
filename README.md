# Information Sharing Network Demonstrator Site

## Getting started

### Development

Wherever possible the style guide for the source code of this ISN reference implementation will follow a forked version of the [Clojure style guide](https://github.com/vox-machina/clojure-style-guide).

#### Configuration

**Creating the config.edn file**

Copy config.template.edn and customise.

**Creating the deploy script**

Copy deploy.template.sh to deploy.sh and customize for your intended ssh target.

#### Adding a GPG public key for IndieAuth

If you would like an alternative to Github account based OAuth add a GPG public key as 'key.pub' into the 'resources/public' directory.

#### Starting the server in dev mode

```bash
clj -X app.core/-main
```

Or for convenience:

```bash
./run.sh
```

#### Testing the service

```bash
clj -X:test
```

### Production

#### Building and generating a version number in version.edn

You will need to pass a parameter to build to determine how the version number for the project changes.
Passing 'change' will increment the 'patch' component of the version (e.g. 0.1.0 will become 0.1.1).
Passing 'minior' will increment the 'minor' component of the version (e.g. 0.1.3 will become 0.2.0).
When you run the command watch the output carefully for failing tests.

```bash
./build.sh change
```

### Deploying to production

When you run the command watch the output carefully for faliing tests.

```bash
./deploy.sh
```

- Create your isn directory on your server
- Unpack the isn.tgz file which has been copied to your server in the isn directory created above
- Configure the config.edn for production
- Use the sample script in 'scripts' to create a systemd service
- Copy START.template and STOP.template to START and STOP respectively
- Configure the sample START and STOP scripts to point to your systemd service
