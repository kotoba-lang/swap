# swap

[![CI](https://github.com/kotoba-lang/swap/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/swap/actions/workflows/ci.yml)

**Provider-agnostic swap core: an intent, a normalized quote, an execution plan
as data, the safety checks that must pass before anything is signed, and fee
accounting. Two rails reduced to one shape.**

The orchestration layer of the kotoba-lang swap plane (ADR-2607261500).

```
              ┌───────────── swap (this repo) ─────────────┐
  intent ───► │ check → plan (steps as data) → state machine│ ───► executor
              └───┬──────────────────────────────┬─────────┘         (holds keys)
      swap.aggregator                     swap.thorchain
   same-chain EVM, DEX aggregator      native BTC ↔ ETH, memo rail
          │                                     │
        erc20                            org-thorchain
                        swap.fee → treasury (ledger)
```

## Nothing here signs or sends

`plan` returns an ordered vector of steps. An executor — which holds the keys,
and which this library never sees — carries them out and feeds outcomes back as
events.

```clojure
{:step/kind :erc20-approve
 :step/why  "the router (0xdef1…) needs an allowance for 1.5 USDC"
 :chain "ETH" :to <token> :data "0x095ea7b3…" :value "0"}

{:step/kind :native-transfer
 :step/why  "send 0.05 BTC to the THORChain vault to receive at least … of ETH.ETH;
             0.3% affiliate fee to kb is skimmed on-chain by the network"
 :chain "BTC" :to "bc1q…" :amount "5000000" :memo "=:ETH.ETH:0x…:2000…/1/0:kb:30"}
```

`:step/why` is required on every step. A wallet that can't explain a confirmation
prompt in the user's own units shouldn't be asking for one.

This is the `state + event -> next-state + effects` shape this workspace uses for
application logic (ADR-2607201300): product semantics in portable data, mechanism
in a host. The practical payoff is that the entire interesting half — routing,
fee encoding, slippage guards, sequencing — is testable **without a network, a
key, or a chain**. A plan is a value you can assert on.

## What it refuses to do

The core exists to say no. `check` returns *every* problem at once as data, so a
UI can show all of them rather than the first:

| refusal | why it matters |
|---|---|
| `:missing-min-out` | A swap with no minimum-output guard can be sandwiched for an arbitrary fraction of its value. A provider that returns no min-out is unusable, not a warning. |
| `:zero-min-out` | min-out of 0 accepts any output, including ~nothing. |
| `:min-out-below-requested-slippage` | The provider's guard is looser than the tolerance the caller asked for — i.e. the setting isn't being honoured. |
| `:fee-not-encoded` | A fee was requested and the quote came back carrying none. The swap looks fee-bearing and **earns nothing**. Silent, and only discovered when the money doesn't arrive. |
| `:fee-bps-mismatch` | Encoded bps ≠ requested bps, in either direction. |
| `:quote-expired` | Requires `now` to be passed in — this library never reads a clock, so the same (quote, time) always gives the same verdict. |
| `:no-steps` | An empty plan would "succeed" having done nothing. |

`intent` refuses earlier still: a float amount (a `uint256` doesn't fit in a
double), a missing `:decimals` (which would silently mis-scale every amount), a
`:fee-bps` without a `:fee-recipient` (a fee charged and paid to nobody), and any
fee above 1000 bps.

## Taking a fee: three mechanisms, in preference order

This is the commercial question, so `swap.fee` states the trade-offs rather than
implying them by whichever got built first.

1. **`:protocol-affiliate` — use this.** The aggregator or cross-chain network
   skims the fee **on-chain** and pays a recipient you name, as part of the swap
   (0x's `swapFeeRecipient`/`swapFeeBps`, THORChain's affiliate memo field). One
   transaction, no custody, no contract to deploy or audit. **MetaMask's own
   0.875% works exactly this way** — embedded in the quote, not a second
   transfer. (The gas the user pays goes to validators, not to MetaMask.)
2. **`:separate-transfer`** — swap, then transfer the fee separately. Trivial and
   universally available, but it doubles gas and the two transactions aren't
   atomic: the swap can land and the fee transfer fail, leaving the books and the
   chain disagreeing. Supported because some providers offer nothing better;
   never a default.
3. **A custom fee-router contract** — atomic and fully controlled, and also a new
   contract custodying third-party funds: an audit, a deployment key, an upgrade
   story, a permanent liability. `kotoba-lang/engi-witness-escrow` is this
   workspace's only Solidity precedent and is explicitly local-test-only. **Not
   implemented here, on purpose.**

Fees are booked through `kotoba-lang/treasury` — pending on submission,
confirmed only after the transaction confirms on-chain. A fee booked before
confirmation is a fee that can be booked and never received.

## Rail 1 — same-chain EVM (`swap.aggregator`)

Quoting a swap well means splitting across pools, comparing venues, and
re-checking every block. An aggregator already does that *and* exposes the fee
parameter above.

**Vendor mapping is data, not code.** An adapter is a map of which query
parameters carry the fee and where the transaction / expected-out / min-out live
in the response. A vendor rename is a one-line edit.

> **Honest status:** the shipped `:zero-ex-v2` and `:lifi` adapters' field names
> come from published docs and have **not** been smoke-tested against a live API
> key in this repo. Both carry `:verified? false`, and the normalized quote
> surfaces `:verified-adapter?` so a caller can't miss it. `parse-quote` **fails
> loudly** naming the exact path when a field is missing, rather than returning a
> quote with a nil min-out. Flip `:verified?` once a real key has produced a real
> quote.

The plan includes an `:erc20-approve` step **only when the response says the
allowance is actually short** (an unconditional approve costs the user a
transaction they may not need; skipping a needed one makes the swap revert). The
comparison is exact decimal-string arithmetic — a double would corrupt any
allowance above 2^53.

## Rail 2 — native BTC ↔ ETH (`swap.thorchain`)

A swap out of Bitcoin is an **ordinary BTC transfer to a vault address, with a
memo**. No wrapped asset, no custodian, no smart contract on the Bitcoin side
(there is no such thing) — see `kotoba-lang/org-thorchain` for why this is the
only usable native shape.

Two things this driver does that matter:

- **It verifies the returned memo before building any step.** A quote returns
  both the vault address *and* the memo, and the memo says where the output goes
  — so an unverified memo hands a remote server authority over the user's funds.
  A substituted destination, a swapped asset, or a silently dropped affiliate fee
  throws here instead of becoming a transfer.
- **It does not paper over a missing guard.** The memo's `LIM` field *is* this
  rail's on-chain min-out. When the memo carries no limit, `:min-out` is `nil`
  and `check` raises `:missing-min-out` — rather than defaulting to expected-out
  and reporting a guard the network isn't enforcing.

Units are kept explicitly apart: THORChain quotes everything in 1e8 fixed point
regardless of the asset's native decimals, so the quote's 1e8 amount and the
transfer's native amount (satoshis vs **wei**) are different numbers for the same
value.

`preflight` additionally flags a halted chain (a halted chain still publishes a
vault address — paying it means waiting for the halt to lift, or a refund), an
amount at/below the dust threshold, and an amount below the node's recommended
minimum (where fixed outbound fees eat a large fraction of the swap).

## Execution

```clojure
(require '[swap.core :as swap])

(def i (swap/intent {:from usdc :to weth :amount "1.5" :taker addr
                     :slippage-bps 100 :fee-bps 30 :fee-recipient treasury-addr}))

(def q (agg/parse-quote i adapter body req))
(swap/check i q now)                    ;=> {:ok? true} | {:ok? false :problems [...]}

(loop [s (swap/begin i q)]
  (if-let [step (swap/current-step s)]
    (let [tx-id (execute! step)]        ; ← your key lives here, not in this library
      (recur (-> s (swap/advance {:event :submitted :tx-id tx-id})
                   (swap/advance {:event :confirmed :tx-id tx-id}))))
    s))
```

`advance` is pure and terminal states are absorbing: a late confirmation can't
resurrect a finished swap, though it is still recorded — `:events` is the audit
trail.

## Verification

```bash
clojure -M:test    # JVM  — 47 tests, 128 assertions
clojure -M:lint    # 0 errors, 0 warnings
# cljs (same suite; nbb doesn't read deps.edn, so assemble the classpath):
nbb --classpath "$(clojure -Spath -M:test | tr ':' '\n' \
     | grep -E 'kotoba-lang|^src$|^test$' | tr '\n' ':')" bin/run_tests.cljs
```

Both rails are driven with response **fixtures**, not live vendors. The tests are
mostly adversarial: substituted destination, dropped fee, inflated fee, a vendor
field rename, an off-by-one allowance, a min-out that ignores the caller's
slippage, an expired quote, a late confirmation after failure.

## Dependencies

`erc20` (calldata + exact decimal units), `org-thorchain` (memo grammar + quote
verification), `treasury` (fee ledger). No HTTP client, no key handling, no
signing — those belong to `eth-crypto` / `btc-crypto` / `wallet` and the executor.

## License

Apache-2.0
