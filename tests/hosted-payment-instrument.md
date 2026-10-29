# GH119 payment instrument regression and candidate correction

This focused fix is based on merged vendor1 main
`58ad571446f8aca83e9b96d85a40ddbddf1239b7`. It supersedes the separate tests-only
PR2 while preserving its original head `6b5451bdb7b8ff851cbc12e1dc818e2bb5ce9a3d`
and red evidence. The original PR2 branch is unchanged. The focused fix retains
all original tests, adapts their transport isolation, and changes one production
input; it does not duplicate the already merged ownership/TLS source.

The production change adds explicit `paymentInstrumentEnumId:'PiCreditCard'` only
when confirmation creates a new Payment. Accept Hosted is already configured to
show credit cards and disable bank accounts. Existing accepted payments retain
their historical instrument and all other fields. No CIM method ID is invented;
no migration, historical reconciliation, gateway operation or backfill is provided.
Review any future noncard contract separately before changing that hosted setting.

The tests compile actual `confirm#HostedTransaction`, Mantle `create#Payment`, and
Mantle `set#PaymentAutoInfo` XML with the existing Moqui compiler harness. They run
the real HostedHttp resource script, replacing its JDK HTTP client with an in-memory
client; the legacy trust-all helper is rejected. Persistence is isolated in memory.
Only a synthetic transaction-details request at `example.invalid` is accepted by
the fake client. Nothing contacts a network, merchant account, server or database.
This does not prove authenticated runtime/JTA/accounting or financial acceptance.

Use existing cached dependencies only:

```sh
export MOQUI_FRAMEWORK_ROOT=/absolute/path/to/um-moqui-app/framework/framework
export XML_SERVICE_HARNESS=/absolute/path/to/um-moqui/tests/support/XmlServiceHarness.groovy
export MANTLE_USL_ROOT=/absolute/path/to/mantle-usl
python3 tests/run-hosted-payment-instrument-tests.py
python3 tests/run-hosted-profile-owner-tests.py
```

The harness must provide `XmlServiceHarness.compile` and `.value`. The runner uses
cached Groovy5.0.3, FreeMarker2.3.34, the existing compiled Moqui framework and listed
cached libraries; missing prerequisites fail rather than install/download. The
verification used Mantle `4e924102a52de2cb0b23fd7464eed2b14e6da4c1`.

Fresh red/green preparation: adapting the HTTP isolation first reproduced exactly
11 passing cases and the 2 GH119 captured/settled failures through vendor1's real
transport script. Adding the single production input made all 13 pass. The composed
ownership/TLS suite also passed 51/51. Full gateway/runtime acceptance is not claimed.

Controls cover real Mantle missing-instrument default, explicit card and bound-method
derivation; exact order/amount/currency/reference inputs; sequential retry without
duplicate payment/response; unchanged historical fields for existing Authorized,
Delivered and Confirmed payments; and decline/invoice/amount rejection before writes.
The bound-method control demonstrates Mantle derivation, not hosted CIM resolution.
Keep both original regression assertions enabled and preserve the red receipt.

Root reviews the candidate source and exact test evidence before main merge.
Staging receivers must remain held for integration; no financial deployment is
accepted by these isolated tests. Historical reconciliation remains separate.
