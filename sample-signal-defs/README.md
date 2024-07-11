# About

This repository contains the definitions of two sample [signals](https://github.com/information-sharing-networks/signals) that can be used to experiment with the ISN [reference implementation](https://github.com/information-sharing-networks/isn-ref-impl)

# SPS Signal (sps-signal.info-sharing.network)

The SPS sample signal definition describes a selection of fields related to the movements of agri-food goods that might be shared by organisations participating in an [ISN](https://github.com/information-sharing-networks)
A draft of the [signal payload](https://github.com/information-sharing-networks/signals#example-3---a-signal-and-its-metadata-which-is-associated-to-a-payload-of-information-in-a-given-domain) fields is below:

| Field name | Description | Data type | Optionality | Notes |
| --- | --- | --- | --- | --- |
| Commodity code(s) | Specific commodity codes for the goods | (Multiple cnCodes where the resolution or no. digits varies depending on how much is known about the goods at any time) smallest 4 digits String | Required | |
| Commodity description | Plain text description of goods | String | Required | If there are multiple cnCodes how useful is this field ? |
| Country of origin | Country goods/sample originated from | ISO3166 (e.g. GB) | Required | |
| Unit identification | A map of identifiers and identifier types (as key/value pairs) for an incoming unit | May be multiples from a set of identifiers (e.g. container number, trailer registration number, VRN, TRN etc) | Required | |
| TBC Seal ID | A seal identifier | TBC | Optional | |
| CHED Numbers | A set of CHED identifiers | CHED-P or CHED-D | Optional | |
| Exporter EORI Number | TBC | TBC | Optional | Must not be provided if it pertains to a sole trader setup or similar - (consortia will need to guarantee they will not provide if this is the case) |
| Importer EORI Number | TBC | TBC | Optional | Must not be provided if it pertains to a sole trader setup or similar - (consortia will need to guarantee they will not provide if this is the case) |
| Mode | The goods movement mode | Enumeration (e.g. RORO,TBC) | Required | |

An example of a pre-notification signal:

The signal definition can be found [here](https://github.com/information-sharing-networks/isn-ref-impl/blob/develop/sample-signal-defs/sps-signal-def.edn)

# Lab test signal (lab-test-signal.info-sharing.network)
this sample signal illustrates how ISN participants my share information about the results of lab tests

| Field name | Description | Data type | Optionality |
| --- | --- | --- | --- |
| Commodity code | The specific commodity code for the goods | String | Required |
| Commodity description | Plain text description of goods | String | Optional |
| Storage temperature | Storage temperature of goods | Enumeration (e.g. ambient, chilled, frozen) | Optional |
| Country of origin | Country goods/sample originated from | ISO 3166 (e.g. GB) | Optional |
| Test date | Date of lab test | ISO 8601 | Required |
| Batch number | Batch number or lot number | Integer | Required |
| Hazard indicator | Hazard indicator found by lab | Enumeration | Optional |
| Accepted hazard level | Is there an accepted level? | String | Optional |
| Test outcome | Outcome of test | String | Optional |
| Country of lab | Lab location | ISO 3166 | Optional |

The signal definition can be found [here](https://github.com/information-sharing-networks/isn-ref-impl/blob/develop/sample-signal-defs/lab-test-signal-def.edn) 
