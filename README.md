# Information Sharing Network Demonstrator Site
# Contents
- [Local development](#local-development-mac-instructions)
- [Production install](#production-linux-instructions)
- [Authentication](#Authentication)
- [API](#API)
- [Limitations](#limitations)

# Intro
[ISNs](https://github.com/information-sharing-networks) are general purpose information sharing networks. 

Every organisation participating in an ISN has a site (with their own URI) to which they can publish [signals](https://github.com/information-sharing-networks/signals).  

Participant sites can also grant permissions to one another so that signals can be exchanged between sites.

Any ISN participant can reply to a signal contributed by another - this allows additional information related to the original signal - For example, corrections, opinions, claims, attestation etc - to be captured. The [W3C Webmention](https://www.w3.org/TR/webmention/) protocol is used in this reference ISN implementation to permit authenticated parties to associate their replies to signals.

Signals are created using an Indieweb [Microformat 2 'event'](https://microformats.org/wiki/h-event) post type by calling the ISN site 'micropub' endpoint, internally this creates a 'signal' compliant with theinformation-sharing-network [signals protocol](https://github.com/information-sharing-networks/signals/blob/main/README.md#signal-definition).

# Getting started

## Local Development (Mac instructions)

*Prerequisites*
- [clojure](https://clojure.org/guides/install_clojure)
- xcode command line tools
- java runtime

clone this repository

```
git clone git@github.com:information-sharing-networks/isn-ref-impl.git
cd isn-ref-impl
```

create a draft config file

```cp templates/config.template.dev.edn config.edn```

As a minimum you need to change the values for the following properties in the config.edn file (the rest of the properties can be left with the template defaults):

```clojure
{:port 5001
 :site-name "Your test tite name"
 :user "user.example.com" 
 :site-root "http://localhost:5001"
 :data-path "/yourdirectory/isn-ref-impl/data"
 :environment "dev" 
 :dev-site "https://user.example.com" 
...
```
You will need to create the directory specified in data-path and then create a subdirectory within it called "signals" (do not refer to the signals subdir in the data-path above)

to start the server:
```run.sh```

A web server should now be running at http://localhost:5001/

Note although no authentication is done when running in dev mode, you still need to click the "login" button on the front page to view the dashboard contents (the dashboard will initially be empty).

The default dev site configuration (config.template.dev.edn) is set up so the site can process the two sample signals that are pre-installed with this repository (lab-test-signal.info-sharing.network and sps-signal.info-sharing.network)

A brief explanation of what these signals do, and their definitions, can be found [here](https://github.com/information-sharing-networks/isn-ref-impl/tree/develop/sample-signal-defs#readme)

You can use the api-test.sh script to experiment with publishing the sample signals to your dev site:

```
# post a sample SPS signal
api-test.sh -s localhost:5001 -p sps
# retrieve all signal that have been posted to the site
api-test.sh -s localhost:5001 -q all
```
... see the script usage statement for details 

If you are adding support for additional signal types, you need to copy the signal definitions to a directory that is accessible by the account running the ISN service. This would normally be done by cloning the github repository containing the definitions, for example:
```
cd isn-ref-impl
git clone git@github.com:border-trade-demonstrators/btd-1.git
git clone git@github.com:border-trade-demonstrators/btd-2.git
```

...Once the definitions have been stored on the server, the details must be added to the ISN site configuration file (config.edn) by amending the 'authcns' and 'isns' sections:
```clojure

:isns #merge [ #include "btd-2/isn-btd-2.edn" #include "btd-1/isn-btd-1.edn" ]; this tells the service where to find the signal definitions

:authcns {
   :btd-1.info-sharing.network #{
      #ref [:user]
    }
   :btd-2.info-sharing.network #{
      #ref [:user]
    }
 } ; this gives your localhost user permission to create the btd1 and btd2 signal types (the unique name for the signal is defined in the definition file referenced above).
   ; in production you grant permission to other Participant Sites to post signals by adding the domain name(s) in this section.
   ; #ref [:user] is a placeholder for your own domain name and should not be removed
```

You can read more about how the reference implementation works at your new dev site http://localhost:5001/documentation

### Testing the service

```
clj -X:test
```

### Building a new version
The build.sh script will create a tar archive that can be installed to live Participant Sites.  See the usage statement for details.



*Development style guide*

Wherever possible the style guide for the source code of this ISN reference implementation will follow a forked version of the [Clojure style guide](https://github.com/vox-machina/clojure-style-guide).


# Production (linux instructions)
*Prerequsites*
- Clojure
- java run time
- nginx
- an user account configured to clone from github

To get started on creating a new ISN Participant Site, clone repo on the server.

**Step 1** - build the latest version
```
cd isn-ref-impl
./build.sh -b current
```

**Step 2** - create the directory to install the Participant Site service software

```
mkdir /your-directory/isn-site-dir
```

**Step** 3 - configure the service

You can use the configure.sh script to automate the configuration of new sites that will be available on the internet.  Note that to do a full installation, the script needs to run as root and will restart nginx. It is not recommended for use on servers where you already have live services running. 

See usage statement for details.  You will be prompted for the following information
- port number for the service to listen on
- the domain name for this Participant site (e.g site.example.com)
- a description for the site (this will be visible on the website header that will be started on the specified site domain)
- the github account that has been nominated as the controller of the Participant Site (see Authentication below for details)
- the directory to store received signals in (e.g /root/isn-site-dir/data)
- the github repository one or more signal definitions that will be handled by the site (e.g git@github.com:border-trade-demonstrators/btd-1.git).  These  repos will be cloned to site installation directory so that the service has a copy of the signal definitions)
 
The script will then
- Unpack the build file into the isn directory created above
- Configure  config.edn for production
- create a systemctl service script to automatically restart the service on reboot
- create an nginx configuration to handle requests to an ISN site on the specified domain (the nginx config will act as reverse proxy site passing API requests to the ISN service running at the port you specified in the configuration).

You can grant permission to other participant sites to post/get signals from your site by adding them to the 'authcns' section of config.edn 

If your are adding support for new signal types then install the definition projects on the server and add the locations to the 'isns' section

**step 4** stop/start the service manually

Use isn-service.sh to manually stop or start the service, e.g
```
cd isn-site-dir
./isn-service -s # starts service
```

**step 4** - install a new version of the server software, e.g
```bash
cd isn-ref-impl
git pull
./build.sh -b current
./upgrade site -b build/0.11.3.tgz -r /root/isn-site-dir
```

# Authentication
Only authenticated organisations can contribute signals into the ISN via [W3C Indieauth (OAuth-2)](https://www.w3.org/TR/indieauth/). In the reference ISN implementation github is used as the indieauth authentication provider:

1.Your participant site domain name is used to sign-in to both your own site and other participant sites you have permission to use.

2.When you login to a participant site using your domain name, the ISN webapp running on the server will request an authentication endpoint from the specified domain (this is specified in the config.edn file installed on your server). The ISN webapp then redirects the User to their authentication endpoint.

3.The github authentication provider will authenticate login requests when a reference to the Participant site being used to login is present on the User's github profile page. (The link on the github home page should contain a rel="me" property:
```<a href="https://isnsite.example.org" rel="me">isnsite.example.com</a>```

... one way to add this is to create a new "social link" in your github profile)

4.The authorization endpoint issues a temporary authorization code, and sends it to the ISN webapp.

5.The app checks the code with the authorization endpoint, and if the code is valid and if the user’s identifier matches the identifier the authorization endpoint gives, the login is completed and the user can use the webapp.
once you have authenticated on your own participant site you can retrieve the bearer token needed to use the ISN API. This token can be used to submit or retrieve signals from other sites where your site have been added as a contributer. The API bearer token can be found by clicking the "Account" tab in the navigation bar in your site.

You can allow other sites to contribute/read signals to your site by adding their domain names to the authcns section of your config.edn

note no authentication checks are done in dev mode.

## Adding a GPG public key for IndieAuth

If you would like an alternative to Github account based OAuth add a GPG public key as 'key.pub' into the 'resources/public' directory.

# API

## POST
Once you have your bearer token you can post signals to Particpant Sites where you have been granted access.  The data should be posted to the /micropub endpoint of the site.  Here is an example using the sample SPS signal definition referenced above:

```bash

curl -i -X POST -H "Content-Type: application/json" -H "Authorization: Bearer YOUR-BEARER-TOKEN"  -d       '{
          "h": "event",
          "name": "chicken and beef (movement ref: DUH9)",
          "start": "2024-07-13T08:00:00Z",
          "summary": "moving to PortA with ETA 2024-07-13T08:00:00Z",
          "category": [
            "pre-notification",
            "isn@sps-signal.info-sharing.network"
          ],
          "payload": {
            "cnCodes": [
              "010594",
              "020100"
            ],
            "commodityDescription": "Chicken 40%, beef 60%",
            "countryOfOrigin": "GB",
            "chedNumbers": [
              "CHEDA .GB.YYYY.XXXXXXX"
            ],
            "unitIdentification": {
              "containerNumber": "12346"
            },
            "mode": "RORO",
            "exporterEORI": "EORI-EXP-01",
            "importerEORI": "EORI-IMP-01"
          }
        } '  https://isnserver.example.com/micropub
```

if the request is sucessful the server returns a 201 status code and the URI of the created signal will be included in the Location Header, eg ```Location: https://isnserver.example.com/signals/20240711-aa37d3c9-92b88ed2```

Possible error status codes:
- 400: indicates the user has not complied with the specification required to make a signal  (in this demonstrator this generally means that the server can't locate or parse the signal definition edn file associated wth the signal type that was supplied)
- 401: the user's access token is invalid. You can get the valid access token from the 'Account' tab in you ISN participant site. The valid token should replace the words YOUR-TOKEN in the "Authorization: Bearer YOUR-TOKEN" in the request.
- 500: server error - internal error with the ISN software (this can happen when, for instance, the signals subdirectory is not present in the data-path directory specified in config.edn).

*notes*
| Attribute | Description | Optionality |
| --- | --- | --- | 
| h | Indicates the post type we are creating - for signals this is 'event' | Required |
| name | A human readable name for the signal. This is recorded as the 'object' of the signal in the stored data | Required |
| summary | A human readable description of the event described by the signal. This is recorded as the 'predicate' of the signal in the stored data  |	Required |
| start	| Signals may has a start date (ISO 8601) - this can be passed in per '2023-02-07T21:00:00Z'. In the sample SPS singal mentioned above this is used to indicate an ETA for goods arriving in the country. |	Optional |
| end | Every signal has an end or expiry date (ISO 8601) - if not provided in the API call the default in terms of days from now is configured for an organisations ISN site per signal. This can be passed in per '2023-02-07T21:00:00Z' or '2023-02-07 21:00:00'.| Optional |
| category |  There are typically two uses of this field: a domain specific category and isn signal type identifiers.  An example of an isn type identifier is mandatory and consists of the isn identifier with an 'isn@' prefix (e.g isn@sps-signal.info-sharing.network).  It is the combination of these two categories which determines the required fields in the payload. These items should align wih the corresponding signal definition. (this design means it is possible to define multiple payload definitions within a signal type, although this functionality is not currently supported) | Required | 
| correlationId | correlation ids are autimatically created unless supplied by the client.  When the client supplies a previously issued correlationId then the supplied signal will be linked to the original signal (see example below). | Optional |
| isn, permafrag, signalId,  publishedDate | these are system generated | N/A |

the signal is stored as an EDN file on the server:
```clojure
{:category #{"pre-notification" "isn@sps-signal.info-sharing.network"},
 :payload
 {:cnCodes ["010594" "020100"],
  :commodityDescription "Chicken 40%, beef 60%",
  :countryOfOrigin "GB",
  :chedNumbers ["CHEDA .GB.YYYY.XXXXXXX"],
  :unitIdentification {:containerNumber "12346"},
  :mode "RORO",
  :exporterEORI "EORI-EXP-01",
  :importerEORI "EORI-IMP-01"},
 :signalId "92b88ed2-2227-4158-a93f-d76fbdfd8894",
 :permafrag "signals/20240711-aa37d3c9-92b88ed2",
 :start "2024-07-13T08:00:00Z",
 :isn "sps-signal.info-sharing.network",
 :publishedDate "2024-07-11",
 :publishedDateTime "2024-07-11T08:39:40.939765Z",
 :summary
 "chicken and beef (movement ref: DUH9) moving to PortA with ETA 2024-07-13T08:00:00Z",
 :correlation-id "aa37d3c9-8466-47f3-bf3a-5660e534e6a4",
 :end "2024-07-25T08:39:40.939907Z",
 :predicate "moving to PortA with ETA 2024-07-13T08:00:00Z",
 :provider "isnuser.example.com",
 :object "chicken and beef (movement ref: DUH9)"}
 ```

using a correlation id to create a thread of signals:

```bash 
curl -i -X POST -H "Content-Type: application/json" -H Authorization: Bearer YOUR-BEARER-TOKEN" -d  '{
          "h": "event",
          "name": "chicken and pork (movement ref: P94J)",
          "summary": "correction",
          "category": [
            "pre-notification",
            "isn@sps-signal.info-sharing.network"
          ],
          "correlation-id": "92b88ed2-2227-4158-a93f-d76fbdfd8894",
          "payload": {
            "cnCodes": [
              "010594",
              "020300"
            ],
            "commodityDescription": "Chicken 40%, pork 60%"
          }
        } '  https://isnserver.example.com/micropub

```
using the abbreviated syntax to post data:
The POST API can also recieve data using an x-www-form-urlencoded content type, for example:
```bash
curl -i -X POST -H "Authorization: Bearer YOUR-BEARER-TOKEN" -d h=event -d "name=brazil nuts" -d start="2024-03-25T15:00:00.00Z" -d "summary=moving to PortA with ETA 2024-03-25T15:00:00.00Z" -d category=pre-notification -d category=isn@btd-1.info-sharing.network -d "description=cnCode=cnNuts^countryOfOrigin=GB^mode=RORO" https://your-site.my-example.xyz/micropub
```

## get
An example of retrieving data using query parameters:
```
curl -H "Authorization: Bearer YOUR-BEARER-TOKEN"  https://isn-server.example.com/signals?countryOfOrigin=FR
```
Signals can be filtered by any of the metadata fields per the Signals specification including by a single category. At the time of writing there is no way to filter by multiple categories.

It is possible to filter signals to only include those with a published date-time between two date-times. These date range filters can be stacked on top of standard filters
```
curl -H "Authorization: Bearer YOUR-BEARER-TOKEN"  https://isn-server.example.com/signals?from=2024-02-02T07:00:00.00Z\&to=2024-02-02T08:00:00.00Z
```
The above example will return signals published on the 2nd Feb between 07:00 and 08:00 inclusive. The & is escaped as the curl command is run in the terminal.

# Limitations

Note that the reference implementation was created to demonstrate the concept of Signals and has limited functionality:
- there is support for basic data lifecycle management based on expiry dates set in the signal definition (a production implementation would need the ability to set priority, revoke, cancel, supercede etc)
- the ISN service will not accept new signals unless the corresponding signal definition has been declared in the service configuration, however, it does not validate the received data against the signal definition to check for the presence of mandatory items.
- only tested for very small volumes

