-- Bring a pre-ledger database dump up to the V1 baseline shape.
--
-- This is NOT a Flyway migration. Dumps taken from the pre-ledger application
-- (16 tables) predate the V1 baseline (19 tables), so restoring one produces a
-- database Flyway cannot honestly baseline: spring.flyway.baseline-on-migrate
-- would record it as "at V1" while three tables and six columns were missing,
-- and the money migration would then fail on customer_ledger.
--
-- Run this immediately after restoring such a dump and before starting the
-- application. Flyway then baselines a genuinely V1-shaped schema and applies
-- V2 onwards normally.
--
--   mysql -u root DB_NAME < Dump20260910.sql
--   mysql -u root DB_NAME < upgrade_pre_ledger_dump_to_v1.sql
--
-- Every statement is additive; no existing data is modified. The three tables
-- are created empty and are populated by the ledger backfill.

SET FOREIGN_KEY_CHECKS = 0;

-- Credit limit management, added to customer after the dump was taken.
ALTER TABLE `customer`
    ADD COLUMN `credit_limit`         DOUBLE  DEFAULT NULL,
    ADD COLUMN `credit_limit_enabled` BIT(1)  NOT NULL DEFAULT b'0';

-- Running balances and audit timestamps, added to trading_entries.
ALTER TABLE `trading_entries`
    ADD COLUMN `closing_balance` INT NOT NULL DEFAULT '0',
    ADD COLUMN `created_at`      DATETIME(6) DEFAULT NULL,
    ADD COLUMN `opening_balance` INT NOT NULL DEFAULT '0',
    ADD COLUMN `updated_at`      DATETIME(6) DEFAULT NULL;

CREATE TABLE IF NOT EXISTS `customer_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `credit_amount` double NOT NULL,
  `debit_amount` double NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `is_backdated` bit(1) NOT NULL,
  `payment_mode` varchar(255) DEFAULT NULL,
  `reference_id` bigint DEFAULT NULL,
  `reference_type` varchar(255) DEFAULT NULL,
  `running_balance` double NOT NULL,
  `transaction_date` date NOT NULL,
  `transaction_type` enum('OPENING_BALANCE','SALE','PAYMENT','CREDIT_NOTE','DEBIT_NOTE','ADJUSTMENT') NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `customer_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKj7x9wtgx52d869egxusw0ypk9` (`customer_id`),
  CONSTRAINT `FKj7x9wtgx52d869egxusw0ypk9` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `customer_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` double NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `is_deleted` bit(1) NOT NULL,
  `payment_date` date NOT NULL,
  `payment_mode` varchar(255) NOT NULL,
  `received_by` varchar(255) DEFAULT NULL,
  `remarks` varchar(500) DEFAULT NULL,
  `transaction_reference` varchar(255) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `customer_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKj2oc6atfed430p54cpxeswcun` (`customer_id`),
  CONSTRAINT `FKj2oc6atfed430p54cpxeswcun` FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `payment_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `closing_balance` int NOT NULL DEFAULT '0',
  `created_at` datetime(6) DEFAULT NULL,
  `date` date NOT NULL,
  `description` text,
  `opening_balance` int NOT NULL DEFAULT '0',
  `party_id` bigint NOT NULL,
  `payment` int NOT NULL,
  `transaction_id` varchar(100) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKpheydj5injubxr66i8jincnrm` (`party_id`),
  CONSTRAINT `FKpheydj5injubxr66i8jincnrm` FOREIGN KEY (`party_id`) REFERENCES `parties` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
