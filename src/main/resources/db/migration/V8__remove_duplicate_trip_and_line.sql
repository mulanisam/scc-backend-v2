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
-- Idempotent: on the test database every DELETE matches nothing and the re-chain
-- recomputes the balances it already has.

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
-- matches what recalculateBalancesFromDate would produce.
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
-- 5. Bring each customer's cached balance back in step with their ledger.
-- ---------------------------------------------------------------------------
UPDATE `customer` c
   SET c.`balance_amount` = COALESCE((
           SELECT cl.`running_balance`
             FROM `customer_ledger` cl
            WHERE cl.`customer_id` = c.`id`
            ORDER BY cl.`transaction_date` DESC, cl.`id` DESC
            LIMIT 1), 0)
 WHERE c.`id` IN (6, 8, 11, 18, 22, 23, 26, 29, 92, 95, 141, 142, 143, 144, 146,
                  148, 227, 228, 232, 247, 314);
