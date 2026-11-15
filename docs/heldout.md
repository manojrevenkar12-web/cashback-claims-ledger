# Held-out run

The held-out world (`config/heldout.json`) was run once on 2026-11-15, with the code and settings dev ended on
(`config/heldout.lock` holds the hash of the configuration it ran with). The world uses a different seed and season
(12 January to 10 June 2026) and harder conditions than dev:

* 7% hard scans instead of 4%;
* 1.8% fraud copies instead of 1.2%, and 35% of fraudsters click through first instead of 25%;
* 2 points more tracking loss on every network;
* 12% duplicate deliveries instead of 9%, 5% history replays instead of 3%, and 9% long delays instead of 6%;
* sloppier forms (10% order-number typos, 15% wrong dates);
* more gift cards and foreign codes on orders.

**The gate passed on every line.** The desk decided 99.2% of 1,735 claims right and sent 0.9% invalid claims to
networks, using 17.4 support hours against 260.3 for support deciding every claim. The ledger stayed exact to the cent
for every shopper across 12% duplicates and 5% replays. The full table is in the README and in
`reports/heldout.json`.

## What it showed

| truth → what the desk did | claims | why |
|---|---|---|
| already tracked → told to wait | 6 | The network had reported the order, but the report was still in a long delivery delay when the claim came in. The desk saw no report and the order was inside the claim-after window, so it said to wait. The shopper gets the right practical answer but the wrong reason. |
| fraud → filed | 3 | A fraudster clicked through first, copied a real shopper's confirmation and claimed before the real shopper. Nothing the platform sees separates them until the real shopper claims too. Then both go to a person, but the first claim is already filed and the network declines it. |
| eligible → rejected | 2 | Support-person errors on claims the desk handed over. |
| foreign voucher → filed, no click → filed | 1 each | Support-person errors on claims the desk handed over. |
| **eligible → filed at the wrong amount** | **1** | The model misread one letter of "Tarjeta regalo" (a gift card). The line no longer matched the gift-card words, so it counted as eligible. The figures still added up, and the shopper's form agreed on order number, date and total, so one reading was accepted. **The form vouches for the order number, date and total, but not for the line descriptions that decide exclusions.** |

The wrong amount is the one finding that points at the desk's own logic rather than at what the platform cannot see.

## What changed afterwards (measured on dev only)

**A second reading must agree on every line before money moves.** When a reading was accepted because it matched
the shopper's form, and the claim would be filed or sent to a person for a large amount, the desk now reads again.
It accepts the reading only if a second one is identical, line by line. If none is identical within the read limit,
the claim goes to a person as unreadable. The test is `ClaimDeskTest.aMisreadDescriptionCannotSlipThroughOnTheFormAlone`.

On dev:

| | before | after |
|---|---|---|
| right | 99.3% | 99.3% (99.33% → 99.27%) |
| invalid claims sent to networks | 0.58% | 0.39% |
| wrong amounts | 0 | 0 |
| support hours | 15.8 | 16.2 |

The cost is a second model call on claims that move money, plus a few more claims for a person. Right share dips by
0.06 points because those extra support cases carry the person's own error rate.

The fraud case is left open on purpose. Holding every first claim on an order until the claim window closes would
catch it, but would delay every honest shopper by weeks. The better lever is device and payment signals that this
simulation does not model.

The held-out world was not run again, and `config/gate.json` was not moved.
