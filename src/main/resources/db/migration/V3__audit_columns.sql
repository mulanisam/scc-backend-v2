-- Record who created or last changed each business record, and when.
--
-- Only customer_ledger carried timestamps before this, and nothing recorded a
-- user at all. That matters more here than in most systems: a backdated entry
-- rewrites historical ledger balances, so without attribution there is no way
-- to establish who changed a customer's balance, or when.
--
-- customer_payment already had created_at and updated_at, maintained by
-- @PrePersist/@PreUpdate callbacks on the entity. Those callbacks have moved to
-- AuditableEntity, so only the two "by" columns are added there.
--
-- Existing rows predate auditing and are left NULL rather than being attributed
-- to a user who did not create them. New and updated rows are stamped from the
-- authenticated principal, or "system" for unauthenticated work such as the
-- ledger backfill.

ALTER TABLE `sale`
    ADD COLUMN `created_by` VARCHAR(100) NULL,
    ADD COLUMN `created_at` DATETIME(6)  NULL,
    ADD COLUMN `updated_by` VARCHAR(100) NULL,
    ADD COLUMN `updated_at` DATETIME(6)  NULL;

ALTER TABLE `sale_details`
    ADD COLUMN `created_by` VARCHAR(100) NULL,
    ADD COLUMN `created_at` DATETIME(6)  NULL,
    ADD COLUMN `updated_by` VARCHAR(100) NULL,
    ADD COLUMN `updated_at` DATETIME(6)  NULL;

ALTER TABLE `purchase`
    ADD COLUMN `created_by` VARCHAR(100) NULL,
    ADD COLUMN `created_at` DATETIME(6)  NULL,
    ADD COLUMN `updated_by` VARCHAR(100) NULL,
    ADD COLUMN `updated_at` DATETIME(6)  NULL;

-- created_at / updated_at already exist here.
ALTER TABLE `customer_payment`
    ADD COLUMN `created_by` VARCHAR(100) NULL,
    ADD COLUMN `updated_by` VARCHAR(100) NULL;

-- created_at was NOT NULL and set by the entity callback. Auditing now fills it
-- on persist, but relaxing the constraint avoids a hard failure on any row
-- inserted by a path that bypasses auditing.
ALTER TABLE `customer_payment`
    MODIFY COLUMN `created_at` DATETIME(6) NULL;

-- Finding an existing trip for a date/route/vehicle/driver is now done on every
-- sale submission, to warn about a duplicate before it is saved.
CREATE INDEX `idx_sale_details_trip`
    ON `sale_details` (`date`, `route_id`, `vehicle_id`, `driver_id`);

-- Finding the most recent sale on a route drives the backdate confirmation.
CREATE INDEX `idx_sale_details_route_date`
    ON `sale_details` (`route_id`, `date`);

-- NOTE: no UNIQUE constraint on (date, route_id, vehicle_id, driver_id).
-- The production data already contains four duplicate groups (nine rows), one
-- of them a trip booked twice with twenty sale lines on each copy, so a unique
-- index cannot be created without first deciding what to do with those rows.
-- Duplicate submissions are rejected in the service layer meanwhile; the
-- constraint should be added once the existing duplicates are resolved.
