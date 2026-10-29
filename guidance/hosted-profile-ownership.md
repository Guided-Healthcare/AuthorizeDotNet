# Hosted CIM profile ownership (Rod review draft)

Base: Guided-Healthcare/AuthorizeDotNet `4e67c19ba71726f05744835a8723a3be47580a96`.
This fork change is PR-only. It does not deploy, mutate a live database, or contact a merchant account.
The stale local upstream checkout and its origin are preserved.

## Behavior

The patient-only HostedServices `ensure#CustomerProfile` now requires the authenticated party to match
`partyId` and have the Customer role (a dual-role patient remains eligible). Other CIM/admin services
are unchanged. The service locks Party in the existing mint transaction and retains that lock through
verification and the subsequent hosted-page request. The internal
`AuthorizeDotNet.HostedServices.verify#CustomerProfileOwner` capability is `allow-remote=false`.

An existing `Party.gatewayCimId` is only a candidate. Verification sends `getCustomerProfileRequest`
with that ID using the selected `paymentGatewayConfigId` credentials. A successful response must have
one profile, the exact requested ID, and a `merchantCustomerId` equal to `partyId` or exactly
`partyId_` plus the five Base64URL characters used by the existing creator. The profile email must
match the party's one active, owned EmailPrimary contact. Missing, ambiguous, malformed, or mismatched
email fails closed; blank-to-blank is never ownership evidence. A foreign merchant's colliding numeric
ID cannot be reused just because it exists.

Successful creation may store the numeric ID returned for the request generated for that patient.
Duplicate recovery accepts only E00039 and the complete known single-ID duplicate-message shape,
then performs the same authoritative ownership lookup before binding that candidate. Other error
text, concatenated digits, malformed XML/IDs, contradictory result fields, failed transport, and
owner mismatch produce a generic error and no profile binding. No reset, migration, or backfill exists.

All HostedServices HTTP requests (profile create/verify, hosted-page mint, direct transaction details,
unsettled scans, settled-batch scans, and batch transaction scans) use one shared resource-script
transport. Its cached JDK HTTP client retains default certificate/hostname validation, forbids redirects,
bounds connection establishment to 15 seconds and each request to 30 seconds, accepts HTTP 200 only,
and adds no application retry. This replaces this path's use of the framework's legacy trust-all helper;
the global framework and separate generic CIM/admin transports are unchanged. XML is passed using
ResourceFacade.script's temporary additionalContext, never a credential-bearing Moqui service parameter.

Requests are XML-escaped. Hosted response parsing rejects external entities/DOCTYPE and suppresses
parser diagnostics that could echo response text; failures have generic errors without original causes. Responses, payment-profile/card fields, credential-bearing requests, and
exception bodies remain local and are not returned or logged. Only the verified profile ID leaves
the helper internally. The helper does not request unmasked expiration or issuer data.

## Integration boundary

Backend hosted-attempt-v1 must require the exact verification capability before offering NoToken or
claiming/minting a token. Deploy this fork pin together with the reviewed backend claim changes;
merely defining a similarly named service in an old image is not acceptance. No backend PR is merged
or repinned by this change. Full deployment must also check the framework's service TRACE/result and
HTTP debug logging configuration so existing token paths do not log sensitive results.

Legitimate old profiles with an unrecognized merchantCustomerId shape, missing primary email, or an
email changed in either system require staff reconciliation before hosted checkout. This patch does
not repair or trust those records. Merchant/account identity and profile/email migration need an
explicit reviewed process; never replace a candidate with an arbitrary profile to get checkout working.

Real authenticated Moqui/JTA/Postgres locking, the deployed sandbox's actual response shapes, account
ownership, profile/email history, and the full reservation→profile→hosted-token path remain unverified.
The isolated tests do not establish those deployed guarantees. No financial operation or real API call
was performed, and no production enablement is authorized.

## Official protocol references

- Authorize.Net [Get Customer Profile API](https://developer.authorize.net/api/reference/index.html#customer-profiles-get-customer-profile): selected merchant authentication plus customerProfileId;
  response profile contains merchantCustomerId, email, and customerProfileId. It can also include payment
  profiles, which are deliberately excluded from service outputs.
- Authorize.Net [response codes](https://developer.authorize.net/api/reference/features/errorandresponsecodes.html):
  E00039 is duplicate data; E00040 identifies IDs not valid for the selected merchant.
- Authorize.Net [CIM error support article](https://support.authorize.net/knowledgebase/article/000001206/en-us):
  customer-profile duplicate checks use merchantCustomerId/description/email, and profile IDs are not
  transferrable between accounts. The duplicate ID is a lookup candidate, never ownership proof.

## Isolated verification

`tests/HostedProfileOwnerTest.groovy` executes actual HostedServices definitions, the actual production
Groovy script, and the actual Moqui XML action template. Only persistence and HTTP are replaced.
The focused suite covers selected merchant, same/foreign owners, URL-safe historical suffix,
primary email/role/authentication, malformed/error/duplicate responses, escaped XML, persisted-field
behavior, and refusal before the hosted-payment token request. It also executes the real mint,
direct-confirmation, and unsettled→settled-batch→transaction-list→details XML paths, and rejects
redirect/error status without retry. It performs no actual transport.

Use an existing Moqui framework build/cache and existing backend XML harness; install/copy no dependencies:

```sh
export MOQUI_FRAMEWORK_ROOT=/absolute/path/to/um-moqui-app/framework/framework
export XML_SERVICE_HARNESS=/absolute/path/to/um-moqui/tests/support/XmlServiceHarness.groovy
python3 tests/run-hosted-profile-owner-tests.py
```

The runner uses already installed Java 21, Groovy 5.0.3, FreeMarker 2.3.34, and the framework's cached
libraries. Missing prerequisites fail the command instead of downloading dependencies.

Local verification on 2026-10-28: the focused suite passed 51 cases with zero failures after the
original source reproduced 34 ownership/error/escaping failures (4 existing cases passed). The
non-success HTTP cases separately reproduced 4 failures before the transport change, and replacing
the remaining HostedServices calls reproduced 5 legacy-transport failures before the shared helper.
Two parser-diagnostic cases also reproduced failures before generic parsing errors replaced raw XML errors. XML is
well-formed and the actual Moqui XML action template compiles/executes. Full XSD validation is
blocked because the existing framework `common-types-3.xsd` lines 44/48 contain a `\$\{.*`
pattern rejected by local libxml2 before the service schema can compile. No claim of XSD, real
Moqui runtime, deployment, or merchant acceptance follows from the isolated results.
