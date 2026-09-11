# WhatsApp templates to submit to Fast2SMS

A business-initiated WhatsApp message can only use a pre-approved template. This is
the text to submit, the variable order it expects, and what to set afterwards.

Submit these as **utility**, not marketing. A sale receipt and an account statement
are transactional: utility templates are cheaper per message and are not subject to
marketing opt-out handling. Choosing the wrong category is the most common reason a
template like this gets rejected or billed at the higher rate.

Language: **Marathi (mr)**. The existing SMS is Marathi and customers read it; there
is no reason to switch them to English now.

---

## 1. Daily sale summary — 8 variables

The message a customer gets on a day they traded. One per customer per day, not one
per line: a customer with two lines on one trip gets a single message with the day's
totals.

**Body to submit**

```
नमस्कार {{1}},

दिनांक {{2}} चा व्यवहार:
पक्षी: {{3}}
वजन: {{4}} किलो
दर: ₹{{5}} प्रति किलो
रक्कम: ₹{{6}}
जमा: ₹{{7}}

एकूण शिल्लक: ₹{{8}}

धन्यवाद!
```

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

**After approval**

Set the template id in `.env`:

```
FAST2SMS_WA_DAILY_TEMPLATE_ID=<the new id>
```

Until that is set, the daily WhatsApp message is queued and **skipped** with the
reason "The 8-variable WhatsApp daily template is not approved yet." rather than
being sent against the three-variable template, which would be rejected for every
customer at once.

---

## 2. Weekly statement — not yet built

The statement PDF needs a template with a **document header**, which is a different
shape from the two above. Confirm with Fast2SMS whether their WhatsApp endpoint
supports document headers before designing it; their current API takes a message id
and pipe-separated values, which looks text-only.

Whatever carries it, the PDF must not be exposed at a public URL - a statement holds
a customer's balance. Upload it to get a media id, or use a signed, single-use,
short-expiry link.

---

## Existing templates

| Purpose | Id | Variables | Status |
|---|---|---|---|
| SMS, balance only (DLT) | `195555` | 3: name, date, balance | In use |
| WhatsApp, balance only | `12082` | 3: name, date, balance | Approved, used for test sends |
| WhatsApp, payment receipt | `12083` | — | Defined, never used - no payment has been recorded yet |
| WhatsApp, daily summary | — | 8, as above | **To submit** |

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
