# Supplier payables

## What was wrong

What the business owed a supplier lived in one column, `supplier.pending_payment`, and
`createPurchase` finished by overwriting it:

```java
supplier.setPendingPayment(null);                                  // in memory
purchaseRepository.updatePendingAmount(supplierId, totalAmount);   // accumulate
supplier.setPendingPayment(totalAmount);                           // then overwrite it
supplierRepository.save(supplier);
```

The third line threw away the second. So the column held the **last purchase's total**, not
the balance:

| Supplier | Purchases | Owed | Column said |
|---|---|---|---|
| Khan Poultry | 1 | ₹2,43,000 | **₹0** |
| Akbar Poultry | 5 | ₹8,12,500 | **₹1,95,000** |
| Komarla Agrovet | 2 | ₹6,89,040 | **₹3,44,520** |
| | | **₹17,44,540** | **₹5,39,520** |

Understated by **₹12,05,020**. Sales never had this problem, because a customer's balance is
derived from `customer_ledger`. The purchase side had no ledger at all, so there was also no
way to say which purchase a payment settled, and no supplier statement to produce.

Three other things were broken in the same area, all found while fixing this:

1. **`purchase.paid_amount` was never updated.** Written as `0.00` on creation and left there,
   so the one payment on record — ₹50,000 against purchase 2 — left the purchase looking
   entirely unpaid.
2. **A supplier with two purchases on one date could not be paid.**
   `findBySupplierIdAndEntryDate` returns an `Optional`, and Komarla Agrovet has two purchases
   dated 2025-09-06, so Spring Data threw `query did not return a unique result` before
   anything was saved. Paying that supplier for that day was impossible.
3. **A DC scan was filed against the wrong line.** Every row's attach button called one handler
   that appended to a flat list, and the server pairs `files[i]` with `dcDetails[i]` — so a
   scan chosen on line 3 became the evidence for line 1.

## The ledger

`V14__supplier_ledger.sql` creates `supplier_ledger`, mirroring `customer_ledger`, and
backfills every purchase and payment with running balances.

**The balance runs the other way to a customer's.** A customer owes us, so their balance rises
on a debit. We owe a supplier, so theirs rises on a credit:

```
customer_ledger:  running_balance = previous + debit  - credit    (a sale raises it)
supplier_ledger:  running_balance = previous + credit - debit     (a purchase raises it)
```

That is the ordinary reading of a liability account, and it is the one thing about the table
that is not a mirror image — worth knowing before writing a query against it. Nobody sees those
two words on screen: the statement's columns say **Purchase** and **Paid**.

Purchases sort before payments on a shared date, because that is the order the events happened
in. A payment printed above the purchase it settles reads as money paid against nothing.

After the migration, Akbar Poultry's account reads:

```
2024-08-08  PURCHASE  Purchase #1 - poultry     1,57,500              1,57,500
2024-08-09  PURCHASE  Purchase #2                 72,000              2,29,500
2024-08-09  PAYMENT   Payment ref 5555                     50,000     1,79,500
2025-05-10  PURCHASE  Purchase #4                 50,000              2,29,500
2026-01-07  PURCHASE  Purchase #8 - Sachin Patil 3,38,000              5,67,500
2026-03-25  PURCHASE  Purchase #9 - Popat darekar 1,95,000             7,62,500
```

The migration verifies itself before finishing, in a stored procedure — MySQL rejects `SIGNAL`
through the prepared-statement protocol, which is how V13 failed on its first run:

- one ledger row per purchase, or it stops
- the sum of payables equals bought minus paid, or it stops

DDL has already committed by that point and cannot be rolled back, so this is a loud stop
rather than an undo: Flyway records the failure and refuses to go further.

## What the entry screen enforces now

The same rules the sales screens have had all along:

| | Before | Now |
|---|---|---|
| Line amount | whatever the browser sent | recalculated from weight × rate, ₹10 rounding; a mismatch is refused naming the line |
| Date | anything | no future dates; backdating says it will recalculate later balances |
| Missing supplier/vehicle/driver | `RuntimeException` → 500 | 404 naming it |
| Any failure at all | 400 "Failed to process request" | its own status and message |
| Bird weight | unchecked | warns outside 0.8–5 kg a bird, without blocking |
| DC scans | appended to one flat list | kept per line, and shifted when a line is deleted |

The weight check is a warning rather than a rule on purpose. A genuine load of unusual birds
should still be enterable — but 10 birds against 900 kg is a digit in the wrong place, and
nothing caught that before.

## Reports

`GET /adminuser/purchases/payables` — every supplier, most owed first. The dates bound what was
bought and paid inside them; **outstanding is always current**, because what is owed is not a
question about a date range. The screen says so whenever a range is set.

`GET /adminuser/purchases/suppliers/{id}` — one supplier's ledger plus the purchases behind it,
each with its DC lines, the rate paid, trip expenses and how much of it is still outstanding.

Both under `/adminuser`, the same line already drawn for the customer and trading ledgers:
office staff are the people a supplier asks.

Two things these surfaced immediately on real data:

- **purchase 7 is 960 birds for ₹0** — 3,029 kg recorded, nothing owed. It has sat in the data
  since October 2025 and no screen showed it until now.
- **purchases 5 and 6 are identical** — same supplier, same date, same 901 birds, same
  3,445.2 kg, same ₹3,44,520. Both are on the ledger, because deleting one is a business
  decision, not a migration's. Together they are ₹6,89,040 of the payable. If one is a
  duplicate, the payable is ₹3,44,520 lower than it currently reads.

## Trip expenses

`diesel`, `hamali` and `driver_expense` are captured on every purchase and were reported
nowhere. They now appear per purchase and as a total on the supplier account — ₹17,800 for
Akbar Poultry alone. They are deliberately **not** part of what the supplier is owed: they are
the cost of fetching the load, not part of its price.
