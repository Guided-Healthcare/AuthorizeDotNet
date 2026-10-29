# GH119 offline payment instrument regression

This tests-only draft is based on Guided-Healthcare/AuthorizeDotNet main
`4e67c19ba71726f05744835a8723a3be47580a96`. It does not include or supersede PR1
(profile ownership/TLS, head `3ea34d455ad94039ff8e088585973214af29a246`).
No production gateway operation, financial record, parent pin, or service XML changes.

The suite compiles the real `confirm#HostedTransaction`, Mantle `create#Payment`,
and Mantle `set#PaymentAutoInfo` XML with the existing Moqui action compiler harness.
HTTP and persistence are isolated in memory; the only allowed request is synthetic
transaction-details retrieval at `example.invalid`, intercepted before transport.
Unexpected service boundaries fail rather than contacting an external service.
This is not an authenticated runtime, JTA, database, accounting-posting, or real
merchant test; it does not establish concurrent transaction safety or CIM ownership.

Run using existing dependencies only (no install/build/download):

```sh
export MOQUI_FRAMEWORK_ROOT=/absolute/path/to/um-moqui-app/framework/framework
export XML_SERVICE_HARNESS=/absolute/path/to/um-moqui/tests/support/XmlServiceHarness.groovy
export MANTLE_USL_ROOT=/absolute/path/to/mantle-usl
python3 tests/run-hosted-payment-instrument-tests.py
```

The existing harness must provide `XmlServiceHarness.compile` and `.value`.
The runner requires cached Groovy 5.0.3, FreeMarker 2.3.34, the existing compiled
Moqui framework jar, and its listed cached libraries. Missing prerequisites fail;
nothing is installed. The local run used Mantle commit
`4e924102a52de2cb0b23fd7464eed2b14e6da4c1`.

Expected current result: **11 passing cases, 2 failing cases (exit 1)**. The two
GH119 regressions require newly recorded captured/settled hosted card payments to
be `PiCreditCard`; current production XML supplies neither an instrument nor a
method, so actual Mantle auto-info resolves `PiCompanyCheck`. These failures are
intentional, remain enabled, and must not be interpreted as a passing suite.

Passing controls execute real Mantle auto-info for the absent-instrument default,
explicit credit card, and a bound card method. Additional tests cover preserved
order/amount/currency/transaction inputs, sequential retry without duplicate payment
or response, rejection before writes for decline/invoice/amount mismatch, and
unchanged historical financial fields for existing accepted payment statuses.
The synthetic method control demonstrates Mantle derivation only; it does not
claim the hosted caller currently resolves or validates a CIM payment method.

Rod should review the failing contract and choose the separate production fix:
explicit `PiCreditCard` for a verified hosted card transaction, or a properly bound
CIM payment method. Do not repair historical rows as part of that change. Rerun
these regressions with the reviewed implementation and reconcile with PR1's new
transport before combining branches. No merge, deployment, or gateway call is
authorized by this draft.
