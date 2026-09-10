-- Give money and weight exact decimal types across the sale -> ledger ->
-- customer balance chain.
--
-- Before this migration the same chain used three different representations:
-- whole-rupee INT on sale and sale_details, and floating-point DOUBLE on
-- customer.balance_amount and every customer_ledger column. Binary floating
-- point cannot represent decimal currency exactly, so running balances drifted
-- in a way no report could reconcile.
--
-- Trip weight was the worse problem. sale_details.total_kilogram_sale was INT
-- while sale.kilograms is decimal, so every trip header truncated its own
-- total. Measured on production data before this ran: 1,833 of 2,228 trips did
-- not tally with the sum of their line items, and 9,838.521 kg was missing in
-- aggregate. The final statement recovers those figures from the line items,
-- which hold the true values.
--
-- Purchase, trading and dc_detail money columns are deliberately left alone
-- here; they are normalised in a separate migration so each change stays
-- reviewable on its own.

-- Sale lines ----------------------------------------------------------------
-- rate carries 4 decimal places, not 2. Some historical rates were evidently
-- back-computed as amount / kilograms and kept the full quotient: 124 rows in
-- production hold values such as 114.055 and 123.177. DECIMAL(12,2) silently
-- truncated those (MySQL warning 1265), so the scale matches the data instead.
-- Measured on production: 124 rows exceed 2 dp, 1 exceeds 3 dp, none exceed 4.
-- kilograms never exceeds 3 dp.
ALTER TABLE `sale`
    MODIFY COLUMN `kilograms`       DECIMAL(12,3) NULL,
    MODIFY COLUMN `rate`            DECIMAL(12,4) NULL,
    MODIFY COLUMN `amount`          DECIMAL(14,2) NULL,
    MODIFY COLUMN `payment`         DECIMAL(14,2) NULL,
    MODIFY COLUMN `pending`         DECIMAL(14,2) NULL,
    MODIFY COLUMN `balance_pending` DECIMAL(14,2) NULL;

-- Trip headers --------------------------------------------------------------
ALTER TABLE `sale_details`
    MODIFY COLUMN `total_kilogram_sale`    DECIMAL(14,3) NULL,
    MODIFY COLUMN `total_amount`           DECIMAL(14,2) NULL,
    MODIFY COLUMN `total_payment_received` DECIMAL(14,2) NULL,
    MODIFY COLUMN `total_pending`          DECIMAL(14,2) NULL;

-- Customer balances ---------------------------------------------------------
ALTER TABLE `customer`
    MODIFY COLUMN `balance_amount` DECIMAL(14,2) NOT NULL,
    MODIFY COLUMN `credit_limit`   DECIMAL(14,2) NULL;

-- Ledger --------------------------------------------------------------------
ALTER TABLE `customer_ledger`
    MODIFY COLUMN `debit_amount`    DECIMAL(14,2) NOT NULL,
    MODIFY COLUMN `credit_amount`   DECIMAL(14,2) NOT NULL,
    MODIFY COLUMN `running_balance` DECIMAL(14,2) NOT NULL;

ALTER TABLE `customer_payment`
    MODIFY COLUMN `amount` DECIMAL(14,2) NOT NULL;

-- Recover the truncated trip weights ----------------------------------------
-- The line items are authoritative: each carries its own decimal weight, and
-- the header was only ever their (truncated) sum. Only weight is corrected
-- here. total_amount, total_payment_received and total_bird_sale also disagree
-- with their lines on a minority of trips, but those are whole numbers on both
-- sides, so a mismatch does not indicate truncation and could be wrong on
-- either side. Overwriting them would destroy information rather than restore
-- it, so they are reported instead of silently changed.
UPDATE `sale_details` sd
JOIN (
    SELECT `sale_details_id`, SUM(`kilograms`) AS line_kilograms
    FROM `sale`
    WHERE `sale_details_id` IS NOT NULL
    GROUP BY `sale_details_id`
) line_totals ON line_totals.`sale_details_id` = sd.`id`
SET sd.`total_kilogram_sale` = line_totals.`line_kilograms`;
