-- Extend exact decimal money to the purchase, trading and supplier tables.
--
-- V2 covered the sale -> ledger -> customer chain. These tables were left for a
-- separate migration because they were in a worse state: purchase.diesel,
-- purchase.driver_expense, purchase.hamali and dc_detail.kilograms / rate / nos
-- stored money and quantities as VARCHAR(255) - text - while the remaining
-- columns used INT or DOUBLE.
--
-- Converting them is a prerequisite for the combined sale/purchase reporting:
-- text cannot be summed, and mixing INT rupees with DOUBLE totals reintroduces
-- exactly the drift V2 removed.
--
-- Checked before writing this: across every VARCHAR money column there are no
-- NULLs and no non-numeric values. purchase.hamali holds four empty strings,
-- which are set to NULL below so the conversion does not silently turn them
-- into zero.

-- Empty strings would convert to 0 and be indistinguishable from a real zero.
UPDATE `purchase` SET `hamali`         = NULL WHERE `hamali`         = '';
UPDATE `purchase` SET `diesel`         = NULL WHERE `diesel`         = '';
UPDATE `purchase` SET `driver_expense` = NULL WHERE `driver_expense` = '';
UPDATE `dc_detail` SET `kilograms`     = NULL WHERE `kilograms`      = '';
UPDATE `dc_detail` SET `rate`          = NULL WHERE `rate`           = '';
UPDATE `dc_detail` SET `nos`           = NULL WHERE `nos`            = '';

-- Purchase -------------------------------------------------------------------
ALTER TABLE `purchase`
    MODIFY COLUMN `driver_expense` DECIMAL(14,2) NULL,
    MODIFY COLUMN `diesel`         DECIMAL(14,2) NULL,
    MODIFY COLUMN `hamali`         DECIMAL(14,2) NULL,
    MODIFY COLUMN `total_amount`   DECIMAL(14,2) NULL,
    MODIFY COLUMN `paid_amount`    DECIMAL(14,2) NULL;

-- Delivery challan detail lines ----------------------------------------------
ALTER TABLE `dc_detail`
    MODIFY COLUMN `nos`       INT           NULL,
    MODIFY COLUMN `kilograms` DECIMAL(12,3) NULL,
    MODIFY COLUMN `rate`      DECIMAL(12,4) NULL,
    MODIFY COLUMN `amount`    DECIMAL(14,2) NULL;

-- Trading (wholesale to other traders) ---------------------------------------
ALTER TABLE `trading_entries`
    MODIFY COLUMN `kilograms`       DECIMAL(12,3) NULL,
    MODIFY COLUMN `rate`            DECIMAL(12,4) NULL,
    MODIFY COLUMN `amount`          DECIMAL(14,2) NULL,
    MODIFY COLUMN `payment`         DECIMAL(14,2) NULL,
    MODIFY COLUMN `pending`         DECIMAL(14,2) NULL,
    MODIFY COLUMN `balance_amount`  DECIMAL(14,2) NULL,
    MODIFY COLUMN `opening_balance` DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    MODIFY COLUMN `closing_balance` DECIMAL(14,2) NOT NULL DEFAULT 0.00;

-- Trading parties and their payment history ----------------------------------
ALTER TABLE `parties`
    MODIFY COLUMN `balance_amount` DECIMAL(14,2) NULL;

ALTER TABLE `payment_entries`
    MODIFY COLUMN `payment`         DECIMAL(14,2) NOT NULL,
    MODIFY COLUMN `opening_balance` DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    MODIFY COLUMN `closing_balance` DECIMAL(14,2) NOT NULL DEFAULT 0.00;

-- Suppliers ------------------------------------------------------------------
ALTER TABLE `supplier`
    MODIFY COLUMN `pending_payment` DECIMAL(14,2) NULL;

ALTER TABLE `supplier_payment_hist`
    MODIFY COLUMN `total_amount`    DECIMAL(14,2) NULL,
    MODIFY COLUMN `paid_amount`     DECIMAL(14,2) NULL,
    MODIFY COLUMN `pending_payment` DECIMAL(14,2) NULL;

-- Indexes for the reporting queries ------------------------------------------
CREATE INDEX `idx_purchase_entry_date` ON `purchase` (`entry_date`);
CREATE INDEX `idx_sale_date_customer`  ON `sale` (`date`, `customer_id`);
CREATE INDEX `idx_sale_date_route`     ON `sale` (`date`, `route_id`);
CREATE INDEX `idx_sale_date_driver`    ON `sale` (`date`, `driver_id`);
CREATE INDEX `idx_ledger_customer_date` ON `customer_ledger` (`customer_id`, `transaction_date`);

-- purchase_old and dc_detail_old are legacy tables no longer written to and are
-- deliberately left untouched.
