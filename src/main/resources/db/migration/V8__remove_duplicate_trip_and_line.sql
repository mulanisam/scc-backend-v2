-- Remove one duplicated trip and one duplicated sale line, and re-chain the
-- ledger for the customers they touched.
--
-- Why this is a migration rather than a script. These rows were removed from the
-- test database as data work earlier, and that never propagated: test held 56,099
-- sales against 56,120 in dev and production, and a balance of 2,03,67,247 against
-- 2,03,96,697. A correction applied by hand to one environment is a correction the
-- other environments do not have, which is what this file exists to stop.
--
-- What is being removed, verified in production before writing this:
--
--   sale_details 489 and 490 are twins - both 2025-04-22, route 1, vehicle 4,
--   driver 8, 257 birds sold, 71,140.00 billed, 46,590.00 collected, 20 sale lines
--   each. Trip 490 and its 20 lines (sale 3709-3728) are the duplicate.
--
--   sale_details 2114 and 2115 are twins - both 2025-11-27, route 5, vehicle 2,
--   driver 2, one line of 49.000 kg at 100.0000 for customer 247, 4,900.00. Sale
--   24598 on trip 2115 is the duplicate of sale 24597 on trip 2114.
--
--   Trip 2119 that day is NOT a duplicate - different vehicle and driver, a
--   zero-amount line - and is left alone.
--
-- Rows are named by id rather than matched by a rule. Every rule tried against
-- this data also matched legitimate rows: 43 sales across all three databases are
-- exact duplicates on customer, date, weight, rate, amount and payment, because a
-- customer buying the same weight at the same rate twice on one trip is ordinary.
-- Explicit ids cannot misfire on a production database, and they document exactly
-- what was judged to be wrong.
--
-- The arithmetic closes: the 21 rows carry 76,040.00 billed against 46,590.00
-- collected, so 29,450.00 of debt, and 2,03,96,697.00 - 29,450.00 is 2,03,67,247.00,
-- which is what the test database already holds.
--
-- Idempotent: on the test database every DELETE matches nothing, the balance
-- adjustment in step 5 subtracts zero, and the re-chain in step 4 recomputes the
-- balances it already has.
--
-- ---------------------------------------------------------------------------
-- Fixed after this shipped: step 5 used to read each customer's balance back
-- from customer_ledger rather than adjust it directly, and that is wrong on any
-- database where the ledger backfill has not run yet at this point in the
-- sequence - a fresh environment applying V1 through V14 in one pass, rather
-- than the incremental history this was written against, where the backfill
-- had already happened long before this file did.
--
-- Caught on a disposable restore of the Sohel Chicken Centre shop's own
-- database (never under Flyway before, no prior migration history at all)
-- before it ever touched anything real: with customer_ledger still empty,
-- the old step 5 read nothing for all 21 customers, COALESCEd to zero, and
-- overwrote every one of their balances with 0.00 - wiping real debt rather
-- than reducing it by the 29,450.00 above. It was never wrong on a database
-- where the ledger already existed by the time this ran, which is every
-- environment it had been applied to until now, and why nothing caught it
-- sooner.
--
-- Step 5 below no longer touches customer_ledger at all. It captures what the
-- rows being deleted billed and collected, per customer, before they are gone,
-- and subtracts exactly that from the customer's own cached balance - correct
-- whether the ledger backfill has run, is running now, or has not happened
-- yet, because it never depends on the ledger being there to ask.

-- ---------------------------------------------------------------------------
-- 0. What each affected customer is owed less of, from the rows about to be
--    deleted. Captured now because step 2 removes the only place this is
--    readable from.
-- ---------------------------------------------------------------------------
CREATE TEMPORARY TABLE `v8_duplicate_effect` AS
SELECT `customer_id`,
       COALESCE(SUM(`amount`), 0) AS `billed`,
       COALESCE(SUM(`payment`), 0) AS `collected`
  FROM `sale`
 WHERE `id` IN (3709, 3710, 3711, 3712, 3713, 3714, 3715, 3716, 3717,
                3718, 3719, 3720, 3721, 3722, 3723, 3724, 3725, 3726,
                3727, 3728, 24598)
 GROUP BY `customer_id`;

-- ---------------------------------------------------------------------------
-- 1. The ledger entries for the duplicate sales.
-- ---------------------------------------------------------------------------
DELETE FROM `customer_ledger`
 WHERE `reference_type` = 'SALE'
   AND `reference_id` IN (3709, 3710, 3711, 3712, 3713, 3714, 3715, 3716, 3717,
                          3718, 3719, 3720, 3721, 3722, 3723, 3724, 3725, 3726,
                          3727, 3728, 24598);

-- ---------------------------------------------------------------------------
-- 2. The duplicate sale lines.
-- ---------------------------------------------------------------------------
DELETE FROM `sale`
 WHERE `id` IN (3709, 3710, 3711, 3712, 3713, 3714, 3715, 3716, 3717, 3718, 3719,
                3720, 3721, 3722, 3723, 3724, 3725, 3726, 3727, 3728, 24598);

-- ---------------------------------------------------------------------------
-- 3. The duplicate trip headers, only once nothing is attached to them.
-- ---------------------------------------------------------------------------
DELETE FROM `sale_details`
 WHERE `id` IN (490, 2115)
   AND NOT EXISTS (SELECT 1 FROM `sale` s WHERE s.`sale_details_id` = `sale_details`.`id`);

-- ---------------------------------------------------------------------------
-- 4. Re-chain running_balance for the 21 customers involved.
-- ---------------------------------------------------------------------------
-- Removing a row from the middle of a ledger leaves every later running_balance
-- for that customer overstated. Recomputed here in one pass per customer, in the
-- same order the application uses - transaction_date then id - so the chain
-- matches what recalculateBalancesFromDate would produce. A no-op wherever the
-- ledger backfill has not populated these customers yet - there is nothing to
-- re-chain, and that is fine: it will be built correctly, without these deleted
-- rows, whenever the backfill does run, because they are already gone from sale.
UPDATE `customer_ledger` cl
  JOIN (
        SELECT `id`,
               SUM(`debit_amount` - `credit_amount`)
                 OVER (PARTITION BY `customer_id` ORDER BY `transaction_date`, `id`) AS chained
          FROM `customer_ledger`
         WHERE `customer_id` IN (6, 8, 11, 18, 22, 23, 26, 29, 92, 95, 141, 142,
                                 143, 144, 146, 148, 227, 228, 232, 247, 314)
       ) recomputed ON recomputed.`id` = cl.`id`
   SET cl.`running_balance` = recomputed.chained;

-- ---------------------------------------------------------------------------
-- 5. Reduce each customer's cached balance by exactly what the deleted rows
--    were worth to them - billed less collected - captured in step 0.
-- ---------------------------------------------------------------------------
UPDATE `customer` c
  JOIN `v8_duplicate_effect` d ON d.`customer_id` = c.`id`
   SET c.`balance_amount` = c.`balance_amount` - (d.`billed` - d.`collected`);

DROP TEMPORARY TABLE `v8_duplicate_effect`;
