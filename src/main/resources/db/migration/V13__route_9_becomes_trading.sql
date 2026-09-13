-- Route 9 was never a route. It moves into Trading, with its records.
--
-- "Route no 9 Trading" carries 11 wholesale parties - several named "... trading" outright
-- - and it is not a delivery round: it is the wholesale side of the business, pinned to a
-- fake route so it had somewhere to live. That put it inside every route-based report and
-- trip sheet, where it does not belong.
--
-- What is moving, and it is not small:
--
--   11 parties, 44,03,490 outstanding between them (largest single: 13,40,970)
--   571 sales, 1,09,79,950 billed against 65,76,460 received, March to September 2026
--   61 trips
--   582 ledger rows
--
-- Two things are preserved deliberately, because the alternative is losing them.
--
-- The ledger stays. Statements, the daily message, credit limits and payment reversal are
-- all built on customer + customer_ledger, and trading_entries has no equivalent - it has
-- never held a row. So the customers stay, marked as trading accounts rather than deleted,
-- and their 571 SALE ledger rows are re-pointed at the trading entries that replace their
-- sales. Running balances, the six-month audit trail and the 44 lakh are untouched.
--
-- The trips stay. 571 sales across 61 loads is a vehicle delivering to several parties -
-- the same operational shape as a retail round - and trading had nowhere to record loaded
-- birds, mortality or returns. Rather than drop them, trading gets its own trip table
-- carrying every figure sale_details held.
--
-- On ids: trading_trips and trading_entries are both empty, so each converted row keeps
-- the id of the sale or trip it came from. That is worth more than tidy sequences. The
-- ledger's reference_id already holds the sale id, so re-pointing it becomes a change of
-- reference_type alone - no mapping table, and no matching rows back by date and amount,
-- which is the step that would have silently mis-paired two identical sales on one day.

-- ---------------------------------------------------------------------------
-- 1. Trading gains what it needs to hold this business
-- ---------------------------------------------------------------------------

CREATE TABLE `trading_trips` (
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT,
    `date`                   DATE          NOT NULL,
    `vehicle_id`             BIGINT        NULL,
    `driver_id`              BIGINT        NULL,
    `total_birds`            INT           NULL COMMENT 'Loaded onto the vehicle',
    `mortality`              INT           NULL,
    `return_to_farm`         INT           NULL,
    `loaded_kilograms`       DECIMAL(12,3) NULL,
    `total_bird_sale`        INT           NULL,
    `total_kilogram_sale`    DECIMAL(14,3) NULL,
    `total_amount`           DECIMAL(14,2) NULL,
    `total_payment_received` DECIMAL(14,2) NULL,
    `total_pending`          DECIMAL(14,2) NULL,
    `description`            VARCHAR(255)  NULL,
    `is_correction`          TINYINT(1)    NOT NULL DEFAULT 0,
    `correction_note`        VARCHAR(500)  NULL,
    `created_by`             VARCHAR(100)  NULL,
    `created_at`             DATETIME(6)   NULL,
    `updated_by`             VARCHAR(100)  NULL,
    `updated_at`             DATETIME(6)   NULL,
    PRIMARY KEY (`id`),
    KEY `idx_trading_trips_date` (`date`),
    CONSTRAINT `fk_trading_trips_vehicle` FOREIGN KEY (`vehicle_id`) REFERENCES `vehicle` (`id`),
    CONSTRAINT `fk_trading_trips_driver`  FOREIGN KEY (`driver_id`)  REFERENCES `driver` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT 'A loaded vehicle on the wholesale side. No route: trading is not a round.';

-- The account a trading entry bills to. The ledger is keyed on customer_id and every
-- statement, message and credit check reads it, so a party needs one to be billable.
ALTER TABLE `parties`
    ADD COLUMN `customer_id` BIGINT NULL
        COMMENT 'The ledger account this party is billed through',
    ADD COLUMN `mobile_no` VARCHAR(20) NULL,
    ADD CONSTRAINT `fk_parties_customer`
        FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`),
    ADD UNIQUE KEY `uk_parties_customer` (`customer_id`);

ALTER TABLE `trading_entries`
    ADD COLUMN `customer_id`  BIGINT      NULL
        COMMENT 'The ledger account, so a trading entry bills like a sale',
    ADD COLUMN `trip_id`      BIGINT      NULL COMMENT 'The load this entry came off',
    ADD COLUMN `birds_sold`   INT         NULL,
    ADD COLUMN `payment_mode` VARCHAR(40) NULL,
    ADD COLUMN `obsolete`     TINYINT(1)  NOT NULL DEFAULT 0
        COMMENT 'Superseded by a correction; kept for trace',
    ADD CONSTRAINT `fk_trading_entries_customer`
        FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`),
    ADD CONSTRAINT `fk_trading_entries_trip`
        FOREIGN KEY (`trip_id`) REFERENCES `trading_trips` (`id`);

-- supplier_id was NOT NULL, which the history cannot satisfy: these 571 entries are sales
-- to a party and no supplier was ever recorded against them.
ALTER TABLE `trading_entries`
    MODIFY COLUMN `supplier_id` BIGINT NULL;

-- Retail and wholesale, told apart on the customer record. Needed because the customers
-- stay - the ledger requires it - so without a marker they would keep appearing in route
-- customer lists and retail reports, which is what this migration exists to stop.
ALTER TABLE `customer`
    ADD COLUMN `channel` VARCHAR(20) NOT NULL DEFAULT 'RETAIL'
        COMMENT 'RETAIL for a route shop, TRADING for a wholesale party';

CREATE INDEX `idx_customer_channel` ON `customer` (`channel`);

-- ---------------------------------------------------------------------------
-- 2. The masters: the 11 customers become trading parties
-- ---------------------------------------------------------------------------

UPDATE `customer` c
  JOIN `city` ci ON ci.`id` = c.`city_id`
   SET c.`channel` = 'TRADING'
 WHERE ci.`route_id` = 9;

INSERT INTO `parties` (`name`, `owner`, `city`, `address`, `balance_amount`,
                       `is_obsolete`, `customer_id`, `mobile_no`)
SELECT TRIM(c.`name`),
       TRIM(COALESCE(NULLIF(TRIM(c.`shop_name`), ''), c.`name`)),
       ci.`name`,
       c.`address`,
       c.`balance_amount`,
       c.`obsolete`,
       c.`id`,
       c.`mobile_no`
  FROM `customer` c
  JOIN `city` ci ON ci.`id` = c.`city_id`
 WHERE ci.`route_id` = 9;

-- ---------------------------------------------------------------------------
-- 3. The records: trips, then entries, keeping their original ids
-- ---------------------------------------------------------------------------

INSERT INTO `trading_trips`
       (`id`, `date`, `vehicle_id`, `driver_id`, `total_birds`, `mortality`,
        `return_to_farm`, `loaded_kilograms`, `total_bird_sale`, `total_kilogram_sale`,
        `total_amount`, `total_payment_received`, `total_pending`, `description`,
        `is_correction`, `correction_note`, `created_by`, `created_at`, `updated_by`,
        `updated_at`)
SELECT sd.`id`, sd.`date`, sd.`vehicle_id`, sd.`driver_id`, sd.`total_birds`,
       sd.`mortality`, sd.`return_to_farm`, sd.`loaded_kilograms`, sd.`total_bird_sale`,
       sd.`total_kilogram_sale`, sd.`total_amount`, sd.`total_payment_received`,
       sd.`total_pending`, sd.`description`, sd.`is_correction`, sd.`correction_note`,
       sd.`created_by`, sd.`created_at`, sd.`updated_by`, sd.`updated_at`
  FROM `sale_details` sd
 WHERE sd.`route_id` = 9;

INSERT INTO `trading_entries`
       (`id`, `date`, `party_id`, `customer_id`, `trip_id`, `supplier_id`,
        `vehicle_number`, `birds`, `birds_sold`, `kilograms`, `rate`, `amount`, `payment`,
        `pending`, `payment_mode`, `description`, `balance_amount`, `opening_balance`,
        `closing_balance`, `obsolete`, `created_at`, `updated_at`)
SELECT s.`id`,
       s.`date`,
       p.`id`,
       s.`customer_id`,
       s.`sale_details_id`,
       NULL,
       v.`vehicle_no`,
       s.`birds`,
       s.`birds`,
       s.`kilograms`,
       s.`rate`,
       s.`amount`,
       s.`payment`,
       s.`pending`,
       s.`payment_mode`,
       s.`description`,
       -- The running balance after this entry, read from the ledger row that recorded it,
       -- so a trading entry and the statement state the same figure.
       cl.`running_balance`,
       COALESCE(cl.`running_balance`, 0) - COALESCE(s.`amount`, 0) + COALESCE(s.`payment`, 0),
       COALESCE(cl.`running_balance`, 0),
       s.`obsolete`,
       s.`created_at`,
       s.`updated_at`
  FROM `sale` s
  JOIN `parties` p ON p.`customer_id` = s.`customer_id`
  LEFT JOIN `vehicle` v ON v.`id` = s.`vehicle_no`
  LEFT JOIN `customer_ledger` cl
         ON cl.`reference_type` = 'SALE' AND cl.`reference_id` = s.`id`
 WHERE s.`route_id` = 9;

-- The ledger follows its sale to the trading entry.
--
-- reference_id already holds the right number, because the entry kept the sale's id - so
-- only the type changes. Amounts, running balances and dates are untouched, and the
-- statement for these 11 parties reads exactly as it did before.
UPDATE `customer_ledger` cl
  JOIN `trading_entries` te ON te.`id` = cl.`reference_id`
   SET cl.`reference_type` = 'TRADING_ENTRY'
 WHERE cl.`reference_type` = 'SALE'
   AND cl.`customer_id` = te.`customer_id`;

-- ---------------------------------------------------------------------------
-- 4. Prove it before destroying anything
-- ---------------------------------------------------------------------------
--
-- Every sale must have become exactly one entry, every trip a trip, and no ledger row may
-- still point at a sale that is about to be deleted.
--
-- In a procedure because SIGNAL is one of the few statements MySQL's prepared-statement
-- protocol rejects, and Flyway prepares what it cannot classify - the first attempt at
-- this failed on exactly that, which is how the check came to be tested before it was
-- needed.
--
-- What it protects: DDL above has already auto-committed and cannot be rolled back, but
-- execution stops here, before step 5 deletes anything. A failure leaves the new tables
-- populated and the old sales still present - inconsistent, nothing lost, and the counts
-- in the message say what did not line up.

DROP PROCEDURE IF EXISTS `assert_route9_converted`;

DELIMITER //
CREATE PROCEDURE `assert_route9_converted`()
BEGIN
    DECLARE sale_count INT;
    DECLARE entry_count INT;
    DECLARE trip_count INT;
    DECLARE new_trip_count INT;
    DECLARE stranded INT;
    DECLARE unlinked INT;
    DECLARE problem TEXT DEFAULT NULL;

    SELECT COUNT(*) INTO sale_count FROM `sale` WHERE `route_id` = 9;
    SELECT COUNT(*) INTO entry_count FROM `trading_entries`;
    SELECT COUNT(*) INTO trip_count FROM `sale_details` WHERE `route_id` = 9;
    SELECT COUNT(*) INTO new_trip_count FROM `trading_trips`;

    -- A ledger row belonging to a converted party that still says SALE would be left
    -- pointing at a deleted row, and its statement would lose the line.
    SELECT COUNT(*) INTO stranded
      FROM `customer_ledger` cl
      JOIN `parties` p ON p.`customer_id` = cl.`customer_id`
     WHERE cl.`reference_type` = 'SALE';

    -- Every entry must have found its party and its account.
    SELECT COUNT(*) INTO unlinked
      FROM `trading_entries`
     WHERE `customer_id` IS NULL OR `party_id` IS NULL;

    IF entry_count <> sale_count THEN
        SET problem = CONCAT('converted ', entry_count, ' entries from ', sale_count, ' sales');
    ELSEIF new_trip_count <> trip_count THEN
        SET problem = CONCAT('converted ', new_trip_count, ' trips from ', trip_count);
    ELSEIF stranded > 0 THEN
        SET problem = CONCAT(stranded, ' ledger rows still point at a sale about to be deleted');
    ELSEIF unlinked > 0 THEN
        SET problem = CONCAT(unlinked, ' entries have no party or no ledger account');
    END IF;

    IF problem IS NOT NULL THEN
        SET problem = CONCAT('Route 9 conversion aborted: ', problem);
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = problem;
    END IF;
END //
DELIMITER ;

CALL `assert_route9_converted`();
DROP PROCEDURE `assert_route9_converted`;

-- ---------------------------------------------------------------------------
-- 5. Retire the retail records the trading entries replace
-- ---------------------------------------------------------------------------

DELETE FROM `sale` WHERE `route_id` = 9;
DELETE FROM `sale_details` WHERE `route_id` = 9;

-- The converted rows kept their source ids, so the sequences have to clear the highest
-- one or the next insert collides with a row that is already there.
ALTER TABLE `trading_trips` AUTO_INCREMENT = 100000;
ALTER TABLE `trading_entries` AUTO_INCREMENT = 100000;

-- Route 9 and its eight cities stop being route master data.
--
-- Marked obsolete rather than deleted: the 11 customers still carry a city_id, which is
-- NOT NULL and reaches a route, and those customer rows are what the ledger hangs off.
-- Obsolete keeps them out of every picker and report while leaving the chain intact.
UPDATE `city` SET `obsolete` = 1 WHERE `route_id` = 9;
UPDATE `route`
   SET `obsolete` = 1, `name` = 'Route no 9 Trading (moved to Trading)'
 WHERE `id` = 9;
