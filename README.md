# OfferDesk

Merchants fund most 0% APR promotions at Affirm, tens of thousands of
them, and Affirm's CEO has said that underwriting 0% loans is hard
because small errors turn into unprofitable transactions. OfferDesk is
a small Kotlin service that answers one question per checkout: which
payment plans should this shopper see, so that every merchant-funded
0% offer stays profitable and every plan's total is exact and honest?

> Independent portfolio project. Not affiliated with or endorsed by
> Affirm. All rates, fees, and risk numbers are illustrative.

## See it run

```
./gradlew demo
```

Real output, trimmed (three $900 checkouts):

```
2) Bluefin Outdoor, near-prime shopper [129 ms]
  OFFER Pay in 4                  4 x $225.00     $900.00
  OFFER 0% for 3 months           3 x $300.00     $900.00
  HIDE  0% for 6 months
        merchant fee $31.50 does not cover expected loss
        $21.60 + funding cost $14.44
  HIDE  0% for 12 months
        merchant fee $36.00 does not cover expected loss
        $43.20 + funding cost $26.81

3) Northwind, prime shopper, risk service slow [310 ms]
  degraded: risk timed out after 300ms, using conservative
        default risk
  OFFER Pay in 4                  4 x $225.00     $900.00
  ...
  HIDE  0% for 12 months
        merchant fee $90.00 does not cover expected loss
        $202.50 + funding cost $26.81
```

The same generous merchant that funds every 0% term for a prime
shopper (scenario 1, not shown) gets only Pay in 4 when the risk
service misses its deadline: OfferDesk fails safe, never open.

Run the HTTP API on port 8080:

```
./gradlew run
curl -s localhost:8080/v1/quotes \
  -H 'content-type: application/json' \
  -d '{"merchantId":"m_bluefin",
       "shopperId":"s_near_prime",
       "amountCents":90000,"currency":"USD"}'
```

The response lists `offered` plans (each with its full schedule,
APR, per-payment amount, total, and interest), `hidden` plans with
reasons, a `degraded` list, and a `quoteId`. Seeded merchants:
`m_northwind` (funds 0% up to 12 months), `m_bluefin` (thin
margins), `m_cornerstone` (0% plans turned off). Shoppers:
`s_prime`, `s_near_prime`, `s_subprime`; anyone else is thin-file.

## Design

**Domain** (`domain/`, pure, no I/O). `Money` is a value class over
`Long` cents with overflow-checked arithmetic. Rates are basis
points, so there is no floating point anywhere in a price. Plans are
Pay in 4 (biweekly, first payment at checkout), 0% for N months, and
APR for N months. Interest is simple interest on the declining
principal: each month charges APR / 12 times the principal still
owed, rounded half even to the cent.

**Rounding.** Totals are exact; nothing is lost or invented.

- Even splits (Pay in 4 and 0% plans) put the leftover cents on the
  first installment. Every later payment then equals the advertised
  "per payment" amount, and the odd cents are settled at the start,
  when the shopper is looking at the cart, not as a surprise at the
  end.
- APR plans use the standard level payment, and the final payment
  retires whatever principal is left, so it can differ by a few
  cents. The leftover cannot go first here: each month's interest
  depends on the balance the earlier payments left, so a cent moved
  onto payment 1 changes every interest charge after it.

**Unit economics** (`economics/`). For each eligible plan:

```
profit = merchant fee + interest
       - expected loss - funding cost
```

- merchant fee: the merchant's agreed rate for that plan (0% terms
  carry a richer, term-specific promo fee).
- expected loss: principal x PD over the plan's life x LGD.
- funding cost: annual cost of funds on the principal actually
  outstanding in each period, not the full amount.

A plan is offered when profit reaches a configurable floor.
Otherwise it is hidden with a reason built from the same numbers.
Eligibility runs first: merchant on/off switches, whether the
merchant funds that exact term, and per-plan order limits (Pay in 4
only up to $1,000).

**Parallel fetch with deadlines** (`service/OfferService.kt`).
Affirm's engineering team has written that Kotlin is its default for
new services, citing structured concurrency and predictable behavior
under load. OfferDesk uses exactly that. One `coroutineScope` starts
three `async` calls (merchant terms, risk, cost of funds), each in
its own `withTimeout`:

| Dependency     | Deadline | If it fails or times out        |
|----------------|----------|---------------------------------|
| merchant terms | 200 ms   | standard APR plans only         |
| risk           | 300 ms   | conservative default PD         |
| cost of funds  | 100 ms   | last known rate, flagged stale  |

Every fallback moves toward hiding offers, never toward showing an
unprofitable one. Each degraded dependency is listed in the
response. An unknown merchant throws, which cancels the two sibling
calls; a cancelled request cancels all three.

## Honesty invariants and the tests that prove them

78 tests (`./gradlew test`). Property tests use a tiny seeded
generator, 2,000 random plans per property, over amounts from $50
to $30,000, terms of 3 to 36 months, and APRs up to 36%.

- Installments sum exactly to the stated total.
- Total = principal + interest. No fees exist in the model.
- 0% plans total exactly the principal.
- No compounding: each APR installment's interest equals the monthly
  rate times the remaining principal, recomputed independently.
- Remaining principal strictly declines to zero.
- At most one installment differs from the advertised payment, and
  it is the one the rounding rule says (first, or APR payoff).
- A hand-computed schedule: $1,000 at 12% for 12 months is 11 x
  $88.85 + $88.84, interest $66.19.
- Economics: worked examples checked to the cent, floor boundary
  (at the floor offered, one cent under hidden), exact reason text.
- Concurrency on virtual time (`kotlinx-coroutines-test`): latencies
  of 40, 120, and 30 ms finish at 120 ms, not 190; a hung risk call
  ends at its 300 ms deadline and no 0% plan is offered across 70
  order sizes; cancelling the parent cancels all three children.
- HTTP: response shape, 400 validation with every problem listed,
  malformed JSON, 404 for unknown merchants.

## Build

Requires JDK 21. No database, no Docker.

```
./gradlew test    # all tests
./gradlew demo    # three sample quotes
./gradlew run     # API on :8080
```

## Limits

- Numbers are illustrative. Fees, APRs, and limits are made up.
- The risk model is a stub: a fixed PD and LGD per seeded shopper,
  scaled linearly by plan length. Real underwriting is far richer.
- Interest revenue assumes the plan is repaid; expected loss is
  charged on principal only.
- Clients are in-memory fakes with configurable latency.

MIT licensed.
