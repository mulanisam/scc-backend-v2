-- A second mobile number per customer.
--
-- Why it earns a column rather than being squeezed into the existing field: 118
-- customers have no usable number at all and 11 numbers are shared between two or more
-- customers - one across seven of them. Both of those are the same underlying fact,
-- that a shop is reached on whichever phone is answered, and a single field forces the
-- person doing data entry to choose one and lose the other. The shared numbers are
-- what that looks like today: somebody recorded the number they had, for everyone.
--
-- The send path prefers the primary and falls back to this one, so filling it in makes
-- a customer reachable without disturbing a number that already works.
--
-- Nullable with no default. Most customers will never have a second number, and a
-- default of '' would make "no second number" indistinguishable from "recorded as
-- blank" - the distinction MobileNumberRules.Status.MISSING exists to keep.

ALTER TABLE `customer`
    ADD COLUMN `alternate_mobile_no` VARCHAR(20) NULL
        COMMENT 'Second number for the same shop. The send path falls back to it.'
        AFTER `mobile_no`;

-- Finding a customer by either number. Needed because "is this number already recorded
-- against somebody else" now has to ask about both columns before a statement carrying
-- a balance is sent to it.
CREATE INDEX `idx_customer_alternate_mobile`
    ON `customer` (`alternate_mobile_no`);
