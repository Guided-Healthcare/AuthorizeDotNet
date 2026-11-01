# GH-130: verified hosted transaction metadata

The hosted confirmation service already fetches server-side transaction details,
requires its existing approved response/status combination, checks the order
invoice and amount, then locks/reuses or creates the Payment. These authority,
locking, status transitions and replay checks are unchanged. No new gateway
request, token issuance, payment authorization, response field or log is added.

## Existing field mapping

Only when this confirmation creates a new Payment, its optional sanitized
metadata is stored in these established Mantle fields:

| Gateway transaction detail | Existing field |
| --- | --- |
| authCode | Payment.paymentAuthCode and PaymentGatewayResponse.approvalCode |
| AVSResponse | PaymentGatewayResponse.avsResult |
| cardCodeResponse | PaymentGatewayResponse.cvResult |
| transactionStatus | PaymentGatewayResponse.transactionStatus |

cardCodeResponse is the gateway's verification **response code**, never the
cardholder's CVV. The field names and formats are defined by the official
[getTransactionDetails reference](https://developer.authorize.net/api/reference/index.html#transaction-reporting-get-transaction-details).
The checked-in Mantle UDM contract at
a7126b8568f49323556349cb59105e34147247aa defines these fields; Mantle USL
4e924102a52de2cb0b23fd7464eed2b14e6da4c1 accepts paymentAuthCode through its
existing create#Payment non-PK auto-parameters.

Authorization codes are retained only as exactly six ASCII alphanumeric
characters, a conservative subset of the documented six-character format.
AVS accepts only A/B/E/G/N/P/R/S/U/W/X/Y/Z; card verification accepts M/N/P/S/U.
Each optional code must be a single text-only XML element. Unknown, malformed,
nested or missing codes are omitted without changing payment authority.
Transaction status is retained only from the existing accepted status list:
authorizedPendingCapture, capturedPendingSettlement or settledSuccessfully.
No gateway reason prose, raw XML/JSON, payment/card subtree, credentials,
customer details or arbitrary caller fields are copied.

The existing method may already advance a non-accepted Payment to Delivered or
create a missing base gateway response for an existing Payment. That financial
behavior is unchanged. This patch adds no metadata to either historical path and
does not overwrite existing accepted Payment or gateway-response fields. No new
backfill, migration, historical reconciliation or update service is introduced.

## Card snapshot placement decision remains with Rod

Payment and PaymentGatewayResponse have no dedicated last-four/card-type snapshot
fields in the inspected UDM contract. CreditCard belongs to a PaymentMethod and
has broader card storage semantics; hosted confirmation does not establish that
method binding. This change therefore ignores cardNumber, cardType, expiry and
all card subtrees, including unexpected unmasked responses.

Rod must choose an approved per-payment snapshot field/entity or a rigorously
verified existing method binding, retention and presentation policy before
masked digits/card type can be persisted. Do not repurpose comments, reason
messages, alternate references or an invented payment method for this purpose.
No schema or card-snapshot policy is introduced here.

## Verification and remaining acceptance

Tests compile the actual production confirmation XML and actual Mantle
create#Payment/set#PaymentAutoInfo with the pinned framework template. They use
the real HostedHttp XML parser and an in-process JDK HTTP stub which accepts only
a synthetic transaction-details request at example.invalid. Nothing contacts a
gateway, merchant, database or mail system. New cases cover approved statuses,
optional-code validation, declined/held/unknown and mismatched binding denials,
caller metadata rejection, replay, historical records and unexpected card data.
Inherited instrument and profile-owner/transport tests remain included.

The standalone CI uses only pinned public framework/USL/UDM inputs and the generic
test-only XML harness in this repository. Local verification reuses existing
compiled framework/jars without installing or building dependencies. Facade tests
do not establish live authorization, real JTA concurrency, merchant behavior or
runtime logging policy. Existing service-runner TRACE/result configuration must
still exclude payment/token data; no raw diagnostic output is added here.

Rod source review, the separate masked-card placement decision and separately
authorized synthetic deployment/log/persistence acceptance remain open. No merge,
submodule pin change, deployment or live financial operation is authorized by
this source PR.
