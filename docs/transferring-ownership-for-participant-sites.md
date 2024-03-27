# Transferring ownership for Participant Sites

## Step 1 (remote machine)

Typically this would have been taken care of by running the 'configuration.sh' script (see the 'creating-participant-sites.md' file).
Set the 'rel-me-github' config item in config.edn. It should point to a Github that either you or the receiving part owns.

E.g.

```bash
:rel-me-github "https://github.com/MyAccount1"
```

## Step 2 (Github)

Either you or the receiving party needs to own a Github account (with sufficient security enabled e.g. multi-factor auth).

 - Log in
 - Click on the profile
 - Click on edit profile
 - Change the 'website' field by pasting in the fully qualified domain name for the ISN Participant Site you would like to control ownership of.

E.g.

```bash
https://acme.my-example.xyz
```
