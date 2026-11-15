# Repeatable tasks

**Add a merchant or change a rate.** Edit `config/terms.json` (cookie window, claim window, excluded categories,
our voucher codes, date order). Both the world and the product read it. Run evaluate, gate, readme and seed.

**Teach the desk a new excluded category.** Add the category and its words, in every language you sell in, under
`categories` in `config/terms.json`, and list it in the merchants' `excluded`. Add a case to `ClaimDeskTest`.

**Onboard an affiliate network.** Add it to `networks` in the terms and its signing secret as
`CASHBACK_SECRET_<NETWORK>` in the environment. Its postbacks go to `POST /api/postbacks/<network>` signed with
`X-Signature: sha256=<HMAC of the body>`, or onto the SQS queue the EventBridge rule feeds.

**Try a real model.** Set `ANTHROPIC_API_KEY` and start the service; `AnthropicReader` reads confirmations and the
desk checks its readings exactly as it checks the layout reader's. The evaluation keeps the simulated reader so
results stay comparable.

**Run on AWS stores.** `CASHBACK_STORE=dynamodb` with `DYNAMODB_TABLE` (and `DYNAMODB_ENDPOINT` for DynamoDB Local),
`SQS_QUEUE_URL` for the consumer, `DATABASE_URL` for claims, audit and clicks on Postgres.

**Change who handles support.** Minutes per case and the team's minutes a day are in `config/designs.json`. Never
edit `config/heldout.json` or `config/gate.json` after the held-out run.
