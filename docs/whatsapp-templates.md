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

## 1. Daily sale summary — 8 variables — **SUBMITTED, awaiting approval**

| | |
|---|---|
| `message_id` | **32340** — this is what the send API takes |
| Meta `template_id` | 28406350239005466 |
| Name | `daily_sale_summary` |
| Variables | 8, confirmed by the provider's own `var_count` |
| Category | UTILITY |
| Status | **Pending** |

Registered with:

```bash
curl -X POST "https://www.fast2sms.com/dev/whatsapp/v26.0/25534564426242770/message_templates" \
  -H "Authorization: $FAST2SMS_API_KEY" -H "Content-Type: application/json" \
  --data-binary @daily-template.json
# {"id":"28406350239005466","status":"PENDING","category":"UTILITY"}
```

`FAST2SMS_WA_DAILY_TEMPLATE_ID=32340` is already set. Until Meta approves it, the
daily WhatsApp message is queued and held with "WhatsApp template daily_sale_summary
is not approved (Pending)" - the status is read from the provider, so nothing needs
changing when it flips to Approved beyond `GET /admin/messaging/templates?refresh=true`
to drop the cached list.


The message a customer gets on a day they traded. One per customer per day, not one
per line: a customer with two lines on one trip gets a single message with the day's
totals.

**Body as registered**

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

When Meta approves it, call `GET /admin/messaging/templates?refresh=true` to drop the
cached list and the same messages queue normally. The status is never hardcoded, so
there is no second place to remember to change.

Two guards sit in front of this, and both were proven by pointing the configuration
at the wrong template on purpose:

- the variable count is compared against the provider's own `var_count` - "Template
  pending_balance takes 3 variables but 8 were supplied"
- an unapproved template is refused by status, whatever its variable count

---

## 2. Weekly statement — possible, not yet built

Fast2SMS **does** support this, which was an open question:

- `POST /dev/whatsapp/{version}/{waba_id}/message_templates` with a media template
  carries a document header
- `Upload Media` returns a media id for the PDF, so the statement never needs a
  public URL - which matters, because it holds a customer's balance
- `Send Session Message` sends free-form text or media inside the 24-hour window
  after a customer replies, with no template needed

The blocker is not the provider. It is that the statement PDF is generated by jsPDF
in the browser, so a weekly scheduled job on the server cannot produce one. That
generator has to move server-side first - see the Stage C note in the plan.

---

## Existing templates

| Purpose | Id | Variables | Status |
|---|---|---|---|
| SMS, balance only (DLT) | `195555` | 3: name, date, balance | In use |
| WhatsApp, balance only | `12082` | 3: name, date, balance | Approved, used for test sends |
| WhatsApp, payment receipt | `12083` | — | Defined, never used - no payment has been recorded yet |
| WhatsApp, daily summary | `32340` | 8, as above | **Pending** Meta approval |

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
POST /admin/messaging/test
{ "mobileNo": "7798112855", "name": "Sohel", "channel": "WHATSAPP" }
```

The response carries the exact body, the variables, and the provider's reply.
