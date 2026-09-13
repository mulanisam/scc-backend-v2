# WhatsApp templates

A business-initiated WhatsApp message can only use a pre-approved template. This is
what exists on the account, what each template takes, and what the application does
while one is waiting for approval.

Submit these as **utility**, not marketing. A sale receipt and an account statement
are transactional: utility templates are cheaper per message and are not subject to
marketing opt-out handling. Choosing the wrong category is the most common reason a
template like this gets rejected or billed at the higher rate.

Language: the templates are registered as **`en`** while carrying **Marathi** text.
That is not an oversight - it is what the three existing approved templates do, and
Meta accepts it. Matching them avoids introducing a second language code for no
benefit.

---

Templates can be created through the API rather than the dashboard. The endpoints
are under `POST /dev/whatsapp/{version}/{waba_id}/message_templates` - a text
template, a media template (which is what a statement PDF needs), and a CTA template
that adds buttons. `GET /dev/dlt_manager/whatsapp?type=template` lists what exists,
and there are delete endpoints by id and by name.

Account: WABA `25534564426242770`, phone_number_id `943210575552456`.

---

## 1. Daily sale summary — two versions

The message a customer gets on a day they traded. One per customer per day, not one
per line: a customer with two lines on one trip gets a single message with the day's
totals.

There are two of these because the owner does not want the per-kilo rate on a
customer's phone. **`daily_sale_no_rate` is what the application sends**;
`daily_sale_summary` stays approved and available from the messaging screen's Send tab.

| | `daily_sale_no_rate` | `daily_sale_summary` |
|---|---|---|
| `message_id` | **32367** | 32340 |
| Meta `template_id` | 1364394942351128 | 28406350239005466 |
| Variables | 7 | 8 |
| Category | UTILITY | UTILITY |
| Status | **Approved** (submitted and approved 11 Sep 2026) | **Approved** |
| Rate line | no | yes |

Two builders, not one with a flag: `SmsMessageBuilder.dailySaleSummaryNoRate` and
`dailySaleSummary`. A flag that dropped one value would shift every later variable into
the wrong slot — the balance would print as the amount. Which one runs is decided by
`messaging.fast2sms.whatsapp-daily-includes-rate`, and it must agree with the configured
template id. A mismatch is caught before anything is sent: the queue checks the value
count against the provider's own `var_count` and holds the message with a reason.

**Passing 0 in the rate slot was the other option, and it is worse.** The label is part
of the approved body — `दर: ₹{{5}} प्रति किलो` — so a zero renders as a rate of zero,
which reads as a billing fault rather than as a withheld figure.

Worth being clear about what removing the line achieves: amount and weight are both
still in the message, so `रक्कम ÷ वजन` gives the rate back in one division. It keeps the
figure off a screen somebody might hold up in a market; it is not confidentiality.

Registered with:

```bash
curl -X POST "https://www.fast2sms.com/dev/whatsapp/v26.0/25534564426242770/message_templates" \
  -H "Authorization: $FAST2SMS_API_KEY" -H "Content-Type: application/json" \
  --data-binary @daily-template-no-rate.json
# {"id":"1364394942351128","status":"PENDING","category":"UTILITY"}
```

Approved within the hour of submission. The status is read from the provider rather than
recorded here, so this table can go stale without anything breaking — the live answer is
`GET /adminuser/messaging/templates?refresh=true`, or the **Re-check with provider** button
on the messaging screen's Templates tab.

The template is no longer what holds the daily WhatsApp message back. Two things still
do: no customer has opted in, and the WhatsApp number on the account is
`+1555-719-7902`, which looks like Meta's test number rather than the business line.

**Body as registered — `daily_sale_no_rate` (7 variables)**

```
नमस्कार {{1}},

दिनांक {{2}} चा व्यवहार:
पक्षी: {{3}}
वजन: {{4}} किलो
रक्कम: ₹{{5}}
जमा: ₹{{6}}

एकूण शिल्लक: ₹{{7}}

-सोहेल चिकन,माढा
```

**Body as registered — `daily_sale_summary` (8 variables, rate included)**

```
नमस्कार {{1}},

दिनांक {{2}} चा व्यवहार:
पक्षी: {{3}}
वजन: {{4}} किलो
दर: ₹{{5}} प्रति किलो
रक्कम: ₹{{6}}
जमा: ₹{{7}}

एकूण शिल्लक: ₹{{8}}

-सोहेल चिकन,माढा
```

Plus a `PHONE_NUMBER` button reading **कॉल करा** to +918605030099, and the same
`-सोहेल चिकन,माढा` sign-off - both copied from the two approved templates, so a
customer receiving this recognises it as coming from the same place.

**Variables, in order**

| # | Meaning | Example | Where it comes from |
|---|---|---|---|
| 1 | Customer name | `Mainuddin Kazi` | `customer.name` |
| 2 | Date of the trading day | `10-09-2026` | the sale date, not today |
| 3 | Birds supplied | `15` | sum of the day's sale lines |
| 4 | Weight in kg | `30.0` | sum of the day's kilograms |
| 5 | **Rate per kg** | `100.00` | amount ÷ weight, derived |
| 6 | Amount billed | `3000` | sum of the day's amounts |
| 7 | Paid today | `500` | sum of the day's payments |
| 8 | Total balance | `2500` | the ledger's closing balance |

**Why these eight.** Every one is a figure a customer would otherwise telephone to
ask about, and each answers a different question:

- **Rate** is the one that matters most and was missing from the first draft, which
  gave weight and amount and left the customer to divide one by the other. It moves
  daily and it is what a disagreement is usually about. It is derived from amount ÷
  weight rather than copied from a sale line, because a customer billed at two
  different rates on one trip has no single line rate - the honest figure for the
  day is the total over the total.
- **Paid and balance are both there.** They are not the same thing. A customer shown
  only the balance cannot tell whether today's payment was recorded, which is the
  most common reason for a call.
- **Birds and weight** together let the customer check the average bird weight, which
  is how they judge a load.

Numbers are sent unformatted - `3000`, not `3,000`. A DLT or WhatsApp template is
matched against its approved content, and the code this replaces sent plain numbers
and had them accepted. The grouped form appears in the stored preview, which is what
support reads.

**What happens while it is Pending**

Nothing needs doing. The daily WhatsApp message is queued and held with the reason
read from the provider:

```
WhatsApp template daily_sale_summary is not approved (Pending)
```

When Meta approves it, call `GET /adminuser/messaging/templates?refresh=true` to drop the
cached list and the same messages queue normally. The status is never hardcoded, so
there is no second place to remember to change.

Two guards sit in front of this, and both were proven by pointing the configuration
at the wrong template on purpose:

- the variable count is compared against the provider's own `var_count` - "Template
  pending_balance takes 3 variables but 8 were supplied"
- an unapproved template is refused by status, whatever its variable count

---

## 2. Weekly statement — built, awaiting template approval

| | |
|---|---|
| `message_id` | **32614** |
| Meta `template_id` | 1962010027714038 |
| Name | `weekly_statement` |
| Variables | 4: name, from, to, closing balance |
| Header | DOCUMENT — the statement PDF |
| Status | **Pending** (submitted 12 Sep 2026) |

`WeeklyStatementJob` runs 06:30 Monday IST for the Monday-to-Sunday week that just
closed, rendering each customer's statement with the same server-side generator the
Download button uses — which is what unblocked this. The old blocker was that the PDF
was drawn by jsPDF in the browser, and a scheduled job has no browser.

Two switches, deliberately separate: `messaging.weekly-statement-enabled` decides
whether the statements are **built**, `messaging.enabled` whether they are **sent**. With
the first on and the second off, a week's statements sit in the outbox to be read before
anything reaches a customer.

**Registering a DOCUMENT template takes an extra step.** Meta rejects
`header_handle` containing a URL —

```
Templates with DOCUMENT header type need an example/sample, but it was not provided.
```

— so a real file has to be uploaded first to get a handle:

```bash
# 1. A handle for the sample PDF (note: media_handle, not media)
curl -X POST "https://www.fast2sms.com/dev/whatsapp/v26.0/943210575552456/media_handle" \
  -H "Authorization: $FAST2SMS_API_KEY" \
  -F "messaging_product=whatsapp" -F "type=application/pdf" \
  -F "file=@sample-statement.pdf;type=application/pdf"
# {"h":"4::YXBwbGljYXRpb24vcGRm:ARZgu3..."}

# 2. That handle goes in components[0].example.header_handle, then:
curl -X POST "https://www.fast2sms.com/dev/whatsapp/v26.0/25534564426242770/message_templates" \
  -H "Authorization: $FAST2SMS_API_KEY" -H "Content-Type: application/json" \
  --data-binary @weekly-statement-template.json
# {"id":"1962010027714038","status":"PENDING","category":"UTILITY"}
```

Sending is a third endpoint again. `media_handle` is for the template's example;
`POST .../media` returns a **media id** for a real send, and the message goes to
`POST .../messages` in Meta's own JSON shape rather than the query-string form the daily
message uses. `Fast2SmsClient.uploadDocument` and `sendDocumentTemplate` do both.

The PDF is uploaded per send and never stored. It is derived from the ledger in
milliseconds, and keeping 300 of them on disk would mean a directory to back up, prune
and secure, holding documents that each state a customer's balance.

**What a real run does today.** Against production, for the week ending 7 September:

| | |
|---|---|
| Queued | 0 |
| Skipped — not opted in to WhatsApp | **224** |
| Skipped — no mobile number | 28 |
| Skipped — number too short | 2 |
| Nothing to report that week | 233 |

So the template approval is not the thing standing in the way. **224 customers would
receive a statement the moment they are opted in** — consent is the blocker, and it is a
business decision, not a technical one.

### Watching a run

Messaging → **Statements**. One line per week rather than per message, because the
question asked of the screen is "did last week's statements go out", and 254 rows do not
answer that. Selecting a week lists its messages, filtered to sent, failed, queued or not
sent, with a resend on each failure.

A statement gets its own tab rather than a filter on the WhatsApp log because it fails
differently. The daily message is one line of text that either goes or does not; a
statement is rendered, uploaded and then sent as a media template, so it can fail at three
separate points — and mixed into 200 daily rows, a week of statement failures is invisible.

Resending re-renders the PDF from the ledger **as it stands now**, so a figure corrected
since the first attempt is corrected on the new document. The failed attempt stays on
record; the retry is a new row.

Two notes on the counts. *Accepted* and *Delivered* are separate columns and never added
together — Fast2SMS accepting a statement is not a customer receiving one, and the delivery
rate reads `—` rather than 0% until some verdict exists. And *Not sent* is not a failure:
those are customers with no number or no consent, which is fixed on the contact, not by
sending again — which is why no resend is offered on them.

---

## Existing templates

| Purpose | Id | Variables | Status |
|---|---|---|---|
| SMS, balance only (DLT) | `195555` | 3: name, date, balance | In use |
| WhatsApp, balance only | `12082` | 3: name, date, balance | Approved, used for test sends |
| WhatsApp, payment receipt | `12083` | 3: name, date, amount | Approved; `SmsMessageBuilder.paymentReceived` |
| WhatsApp, daily summary, with rate | `32340` | 8, as above | Approved |
| WhatsApp, daily summary, no rate | `32367` | 7, as above | **Approved — what the app sends** |
| WhatsApp, weekly statement | `32614` | 4 + document header | Pending |

This table is a snapshot and the live answer is the provider's. Nothing in the code reads
it: `GET /adminuser/messaging/templates?refresh=true` is the source of truth, so the table
going stale delays nothing.

---

## Checking the wording before anyone receives it

The message is stored in full on every outbox row, so it can be read before a single
customer is messaged:

```sql
SELECT recipient_name, channel, status, skip_reason, variables, body_preview
  FROM message_outbox
 WHERE reference_date = '2026-09-10'
 ORDER BY id;
```

With `MESSAGING_ENABLED=false` a whole day can be queued and read this way without
anything being sent. To see one rendered immediately, send a test to your own number:

```
POST /adminuser/messaging/test
{ "mobileNo": "7798112855", "name": "Sohel", "channel": "WHATSAPP" }
```

The response carries the exact body, the variables, and the provider's reply.
