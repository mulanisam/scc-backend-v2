-- Mark corrected trips, and make weight loss measurable.
--
-- Corrections
--   Trip 4 and trip 5 (2024-08-11, route 3, customer 28) share a date, route,
--   vehicle and driver and both record 45 birds fully paid, but one says 100 kg
--   / 10,000 and the other 92 kg / 9,200. They are not a duplicate submission -
--   the figures differ - so neither was deleted during de-duplication. Both are
--   kept and flagged as a correction pair, which preserves the history and stops
--   them being reported as a duplicate fault.
--
--   This is also why (date, route_id, vehicle_id, driver_id) still carries no
--   UNIQUE constraint: a correction legitimately shares the trip key with the
--   entry it corrects. The service warns on a repeat submission instead, which
--   is the check that actually prevents a fresh double-booking.
--
-- Loaded weight
--   A trip records the birds loaded at the farm but never their weight, and
--   nothing links a trip to the purchase it came from. Weight loss in transit
--   therefore cannot be derived from any existing column. loaded_kilograms adds
--   somewhere to record it; history stays NULL, so the reconciliation report
--   reports weight loss only where the figure is actually known rather than
--   inventing a zero.

ALTER TABLE `sale_details`
    ADD COLUMN `is_correction`    BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN `correction_note`  VARCHAR(500)  NULL,
    ADD COLUMN `loaded_kilograms` DECIMAL(12,3) NULL;

UPDATE `sale_details`
SET `is_correction`   = TRUE,
    `correction_note` = 'Correction pair: trips 4 and 5 record the same load '
                        'for customer 28 (45 birds, fully paid) at different '
                        'weights - 100 kg / 10,000 and 92 kg / 9,200. Both '
                        'retained; neither is a duplicate submission.'
WHERE `id` IN (4, 5);

-- Reporting reads this to separate corrected trips from ordinary ones.
CREATE INDEX `idx_sale_details_correction` ON `sale_details` (`is_correction`);
