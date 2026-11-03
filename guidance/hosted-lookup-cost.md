# GH-148 hosted confirmation lookup modes

`confirm#HostedTransaction` takes `lookupMode`:

- `recovery` (default when omitted): unchanged full search — paginated unsettled list, then the 30-day settled-batch scan, then `getTransactionDetails`. Used by manual "CHECK PAYMENT STATUS" and any legacy caller.
- `routine`: used by the automatic fallback poll. Reads only the first unsettled page (1000 rows, newest first). No match costs **one** gateway call and returns uncertain. A match on our own invoice proceeds in the same invocation to the single details call and the existing invoice/amount/idempotent binding guards — **two** calls, once per checkout, after which polling stops.

A supplied callback `transId` skips the list entirely in either mode (one details call, client-transId invoice guard).

## Coverage tradeoff (accepted by Rod, 2026-11-02)

Routine mode cannot see a lost-callback charge that has already settled or sits beyond the first 1000 unsettled rows. At UM volume and a 180s checkout window both are near-impossible; manual recovery still performs the full search. Empty, failed or truncated lookups are uncertainty, never proof of no charge or permission to mint another token.

## Why no age threshold

Settlement follows the merchant's daily cutoff ([Authorize.Net batch settlement](https://support.authorize.net/knowledgebase/article/000001244/)), so token age does not predict whether a charge settled.

## Diagnostics

Every gateway request logs `HOSTED_CONFIRM_GATEWAY_CALL mode=<routine|recovery> operation=<unsettled|settled_batches|settled_transactions|details> call_count=<n>` (cumulative per invocation, includes pagination and failures). No order/transaction IDs, credentials, amounts or payloads. `gatewayCallCount` is also returned.
