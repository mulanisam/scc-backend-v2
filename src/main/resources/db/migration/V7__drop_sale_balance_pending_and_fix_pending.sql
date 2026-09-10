-- Remove the third running balance, and correct the sale rows whose pending
-- figure does not equal amount minus payment.
--
-- Background. Three separate places tracked what a customer owes:
--
--   customer_ledger.running_balance   the ledger's own chain
--   customer.balance_amount           a per-customer cache
--   sale.balance_pending              a per-sale-row snapshot
--
-- The first two agree exactly - checked before writing this: 0 of 412 customers
-- disagree. The third does not, on 24,501 of 56,099 sales, with a worst gap of
-- 27,05,000. Nothing reads it: no screen, no report, no SMS. SalesDetailRow
-- explicitly takes the running balance from customer_ledger instead, with a
-- comment saying why.
--
-- The reason it drifted is in the code that maintained it. The value was computed
-- as "the previous sale's balance_pending plus this row's pending", where the
-- previous sale was found with findTopByCustomerIdOrderByIdDesc - ordered by id
-- rather than by date, so a back-dated entry chained onto whatever was entered
-- last rather than what came before it chronologically. It was also computed
-- before saveAll, so when the same customer appears twice on one trip - which has
-- happened on 175 trips - both rows chained onto the same earlier row and the
-- second one silently lost the first one's pending. Nothing ever recalculated it
-- afterwards, unlike the ledger, which is re-chained on every back-dated insert.
--
-- It is dropped rather than repaired. A derived value with no reader, no
-- maintenance and no test is a liability, and customer_ledger already answers the
-- same question correctly.

-- ---------------------------------------------------------------------------
-- 1. pending must equal amount - payment.
-- ---------------------------------------------------------------------------
-- 60 rows disagree, all inherited from the previous application; SaleMapper has
-- derived this correctly through MoneyRules.calculatePending since the rewrite,
-- so no new row can be wrong. The differences are small - 52 within 20 rupees,
-- 6 between 21 and 500, 2 above - and net to +1,106 across the whole table.
--
-- pending feeds the reports only. The ledger is built from amount and payment and
-- is not touched by this, so no customer balance changes: verified by comparing
-- customer.balance_amount against the ledger before and after.
--
-- Two rows are worth naming because they are entry errors rather than rounding:
--   sale 30049  11,040.00 billed, 14,100.00 paid, pending stored as -2,160.00
--               (should be -3,060.00)
--   sale 30383   5,980.00 billed,  5,850.00 paid, pending stored as -1,790.00
--               (should be    130.00)
-- One row also carries payment = -1.00, which is meaningless but is left alone:
-- correcting a payment would change the ledger and the customer's balance, and
-- that needs a decision from the business rather than a migration.

UPDATE `sale`
   SET `pending` = COALESCE(`amount`, 0) - COALESCE(`payment`, 0)
 WHERE ABS(COALESCE(`pending`, 0) - (COALESCE(`amount`, 0) - COALESCE(`payment`, 0))) > 0.005;

-- ---------------------------------------------------------------------------
-- 2. Drop the column.
-- ---------------------------------------------------------------------------
-- The Sale entity drops the matching field in the same change, so
-- ddl-auto=validate stays satisfied.

ALTER TABLE `sale` DROP COLUMN `balance_pending`;
