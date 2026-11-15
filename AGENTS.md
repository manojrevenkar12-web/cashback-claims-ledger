# Working in cashback-claims-ledger

Fast feedback first: `./gradlew build` compiles every module and runs every test (core, world, evaluation,
invariants, the Spring service). `cd web && npm test && npm run typecheck` for the console. After any change to the
product, the world or the settings run `./gradlew :eval:evaluate :eval:gate :eval:readme :eval:seed` and commit the
regenerated `reports/dev.json`, `README.md` and `demo/seed.json`; CI fails when they disagree with the code.

## Modules

| module | what it is | may depend on |
|---|---|---|
| `common` | JSON and keyed randomness | nothing |
| `product` | ledger, claims desk, readers, audit; standard library only | `common` |
| `service` | Spring Boot 4: REST, GraphQL subgraph, Postgres, DynamoDB, SQS | `common`, `product` |
| `world` | the simulated shoppers, merchants, networks, card scheme and people | `common` |
| `eval` | runs the designs on the world and writes the reports | all but `service` |

## Invariants (checked in `eval/src/test/java/cashback/eval/InvariantsTest.java` and the tests)

1. **Nothing that serves reads the truth.** `product` and `service` never import `world` or `eval`; the Gradle
   module graph makes it impossible and the invariants test checks the sources. The demo seed holds only what the
   platform receives (clicks, delivered events, filed claims).
2. **The world does not read the product's settings.** `world` reads `config/terms.json` (the published programme
   terms every party knows) and `config/world.json`, never `product.json`, `designs.json` or `gate.json`.
3. **Facts are kept; the reward is computed from the set.** The ledger never overwrites a network's or card scheme's
   report. Duplicates, replays and reordering cannot change a balance. Every store write is compare-and-set on a
   version, so parallel consumers agree with one consumer (the evaluation checks this with 8 threads).
4. **Money is integer cents.** Cashback and eligible values round down as the terms say. The world computes them with
   its own BigDecimal engine and a test checks both engines agree on every order.
5. **The model reads and code decides.** A reading counts only if its figures add up and either the shopper's own
   form or a second reading agrees. The terms, the click log and the ledger decide. The model is never asked whether
   a claim is valid. Pasted confirmations are data.
6. **A claim is filed once.** The claim id is the idempotency key end to end; a person approves claims over the
   support threshold. Every decision is in the hash-chained audit trail with the API key holder as actor.
7. **Same luck for every design.** Every random draw is keyed by (seed, purpose, ids) in `common/Keyed.java`.
8. **Settings in config.** Terms, thresholds, keys, rates and minutes live in `config/`; the console's colours,
   sizes and durations live in `web/src/shared/theme.css` and `tokens.ts`.
9. **Generated reports.** README numbers come from `reports/`.

## The held-out rule

`config/heldout.json` is run once (`./gradlew :eval:heldout`) with the code and settings dev ended on. It writes
`config/heldout.lock` and `reports/heldout.json`; findings go into `docs/heldout.md`. Fixes are made and measured on
dev. The held-out world is not run again and `config/gate.json` is not moved.
