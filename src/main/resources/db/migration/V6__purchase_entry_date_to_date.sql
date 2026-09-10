-- Make purchase.entry_date an actual date.
--
-- It was VARCHAR(255), which is why PurchaseRepository had to take the date as a
-- String and callers passed date.toString(). Text cannot be bucketed by week,
-- month or year, so the combined bought-versus-sold report could not group
-- purchases by period at all.
--
-- Every existing value is already a well-formed ISO date - checked before
-- writing this: 9 rows, none empty, none failing STR_TO_DATE - so the conversion
-- is lossless.
--
-- An index is added because the comparison report filters purchases by date
-- range on every run.

ALTER TABLE `purchase`
    MODIFY COLUMN `entry_date` DATE NULL;

-- The old varchar column had an index created under its previous type.
DROP INDEX `idx_purchase_entry_date` ON `purchase`;
CREATE INDEX `idx_purchase_entry_date` ON `purchase` (`entry_date`);

-- Note on data coverage, recorded here because it determines whether the
-- bought-versus-sold report means anything: this database holds 9 purchases
-- (7,112 birds) against 56,099 sales (929,501 birds). Purchases are barely
-- recorded, so the report exposes a coverage figure alongside the margin rather
-- than presenting an apparent 28-crore profit as fact.
--
-- Purchases 5 and 6 are also an exact duplicate - same date, supplier, farm,
-- birds, weight and amount on 2025-09-06 - and purchase 7 records 960 birds and
-- 3,029.750 kg with a total_amount of 0. Neither is altered here; both need a
-- decision from the business.
