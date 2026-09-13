-- A ledger for what we owe suppliers.
--
-- Purchases had no ledger. What we owed a supplier lived in one column,
-- supplier.pending_payment, and createPurchase overwrote it with the new purchase's total
-- after accumulating it - so the column held the *last* purchase rather than the
-- outstanding balance:
--
--     Khan Poultry      1 purchase   owed 2,43,000   column said        0
--     Akbar Poultry     5 purchases  owed 8,12,500   column said 1,95,000
--     Komarla Agrovet   2 purchases  owed 6,89,040   column said 3,44,520
--
-- The payable was understated by 12,05,020, and no purchase could be shown as paid or
-- unpaid because nothing recorded which payment settled what.
--
-- This is the same shape as customer_ledger, deliberately: a balance that is derived from
-- transactions rather than stored, so it can be recomputed, audited and reversed. The sales
-- side has worked that way since the baseline and the purchase side never did.
--
-- One difference, and it is the one to be careful about. A customer account is money owed
-- TO us and a supplier account is money owed BY us, so the balance moves the other way:
--
--     customer_ledger:  running_balance = previous + debit  - credit   (sale raises it)
--     supplier_ledger:  running_balance = previous + credit - debit    (purchase raises it)
--
-- That is the accounting-correct reading of a liability account - a purchase credits the
-- supplier, a payment debits them - and it is what SupplierLedgerService applies and what
-- SupplierLedgerTest pins down. Nobody sees these two words on screen: the statement's
-- columns say Purchase and Paid.

CREATE TABLE supplier_ledger (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    supplier_id      BIGINT       NOT NULL,
    transaction_date DATE         NOT NULL,

    -- varchar, not a MySQL ENUM. Hibernate 6 maps @Enumerated(STRING) to a native enum by
    -- default and ddl-auto=validate then rejects a varchar column, but the enum is the
    -- side that costs: adding a transaction type would need an ALTER TABLE on every
    -- environment. customer_ledger is a native enum and is stuck with exactly that.
    transaction_type VARCHAR(30)  NOT NULL,

    reference_type   VARCHAR(40)  DEFAULT NULL,
    reference_id     BIGINT       DEFAULT NULL,

    -- What the row does to the balance. credit raises what we owe, debit reduces it.
    debit_amount     DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    credit_amount    DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    running_balance  DECIMAL(14,2) NOT NULL,

    description      VARCHAR(500) DEFAULT NULL,
    payment_mode     VARCHAR(40)  DEFAULT NULL,
    is_backdated     BIT(1)       NOT NULL DEFAULT b'0',

    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  DEFAULT NULL,

    PRIMARY KEY (id),
    -- The index the statement and the payables list both read on.
    KEY idx_supplier_ledger_supplier_date (supplier_id, transaction_date),
    CONSTRAINT fk_supplier_ledger_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Backfill: every purchase, then every supplier payment.
--
-- Purchases are inserted first so that on a date carrying both, the purchase sorts before
-- the payment that settles it - you buy, then you pay, and a statement showing the payment
-- above the purchase reads as money paid against nothing.
-- ---------------------------------------------------------------------------

INSERT INTO supplier_ledger
    (supplier_id, transaction_date, transaction_type, reference_type, reference_id,
     debit_amount, credit_amount, running_balance, description, created_at)
SELECT p.supplier_id,
       p.entry_date,
       'PURCHASE',
       'PURCHASE',
       p.id,
       0.00,
       COALESCE(p.total_amount, 0.00),
       0.00,  -- computed below, once both kinds of row are in
       CONCAT('Purchase #', p.id,
              CASE WHEN p.farm IS NULL OR p.farm = '' THEN '' ELSE CONCAT(' - ', p.farm) END),
       NOW(6)
  FROM purchase p
 WHERE p.supplier_id IS NOT NULL
 ORDER BY p.supplier_id, p.entry_date, p.id;

INSERT INTO supplier_ledger
    (supplier_id, transaction_date, transaction_type, reference_type, reference_id,
     debit_amount, credit_amount, running_balance, description, created_at)
SELECT h.supplier_id,
       COALESCE(h.date_of_transaction, h.date_of_purchase),
       'PAYMENT',
       'SUPPLIER_PAYMENT',
       h.id,
       COALESCE(h.paid_amount, 0.00),
       0.00,
       0.00,
       CONCAT('Payment',
              CASE WHEN h.trans_id IS NULL OR h.trans_id = '' THEN ''
                   ELSE CONCAT(' ref ', h.trans_id) END),
       NOW(6)
  FROM supplier_payment_hist h
 WHERE h.supplier_id IS NOT NULL
   AND COALESCE(h.paid_amount, 0.00) <> 0.00
 ORDER BY h.supplier_id, COALESCE(h.date_of_transaction, h.date_of_purchase), h.id;

-- The running balance, per supplier, in statement order.
--
-- A window function rather than a cursor: MySQL 8 has them, and the whole point of this
-- column is that it is reproducible by exactly this expression.
UPDATE supplier_ledger l
  JOIN (
        SELECT id,
               SUM(credit_amount - debit_amount) OVER (
                   PARTITION BY supplier_id
                   ORDER BY transaction_date,
                            CASE transaction_type WHEN 'PURCHASE' THEN 0 ELSE 1 END,
                            id
                   ROWS UNBOUNDED PRECEDING) AS balance
          FROM supplier_ledger
       ) computed ON computed.id = l.id
   SET l.running_balance = computed.balance;

-- ---------------------------------------------------------------------------
-- Bring the two stored columns into line with the ledger.
-- ---------------------------------------------------------------------------

-- What we owe each supplier, from their last ledger row. Suppliers with no purchases
-- become 0.00 rather than staying whatever they held.
UPDATE supplier s
   SET s.pending_payment = COALESCE((
        SELECT l.running_balance
          FROM supplier_ledger l
         WHERE l.supplier_id = s.id
         ORDER BY l.transaction_date DESC,
                  CASE l.transaction_type WHEN 'PURCHASE' THEN 0 ELSE 1 END DESC,
                  l.id DESC
         LIMIT 1), 0.00);

-- purchase.paid_amount was written as 0.00 and never updated again, so the one supplier
-- payment on record - 50,000 against purchase 2 - left the purchase looking unpaid.
UPDATE purchase p
   SET p.paid_amount = COALESCE((
        SELECT SUM(COALESCE(h.paid_amount, 0.00))
          FROM supplier_payment_hist h
         WHERE h.purchase_id = p.id), 0.00);

-- ---------------------------------------------------------------------------
-- Verification.
--
-- In a stored procedure because MySQL rejects SIGNAL through the prepared-statement
-- protocol - "This command is not supported in the prepared statement protocol yet" - which
-- is how V13 failed on its first run. DDL has already committed by this point and cannot be
-- rolled back, so this is a loud stop rather than an undo: Flyway records the failure and
-- refuses to go further.
-- ---------------------------------------------------------------------------

DELIMITER //

CREATE PROCEDURE verify_supplier_ledger()
BEGIN
    DECLARE ledger_rows INT;
    DECLARE purchase_rows INT;
    DECLARE payable_from_ledger DECIMAL(16,2);
    DECLARE payable_from_purchases DECIMAL(16,2);

    SELECT COUNT(*) INTO ledger_rows
      FROM supplier_ledger WHERE transaction_type = 'PURCHASE';
    SELECT COUNT(*) INTO purchase_rows
      FROM purchase WHERE supplier_id IS NOT NULL;

    IF ledger_rows <> purchase_rows THEN
        SIGNAL SQLSTATE '45000'
          SET MESSAGE_TEXT = 'Supplier ledger backfill missed purchases: counts differ.';
    END IF;

    -- The balances must add up to bought minus paid. This is the assertion that matters:
    -- it is the number the old column got wrong.
    SELECT COALESCE(SUM(pending_payment), 0.00) INTO payable_from_ledger FROM supplier;
    SELECT COALESCE(SUM(COALESCE(p.total_amount, 0.00)), 0.00)
         - COALESCE((SELECT SUM(COALESCE(h.paid_amount, 0.00)) FROM supplier_payment_hist h), 0.00)
      INTO payable_from_purchases
      FROM purchase p WHERE p.supplier_id IS NOT NULL;

    IF ABS(payable_from_ledger - payable_from_purchases) > 0.01 THEN
        SIGNAL SQLSTATE '45000'
          SET MESSAGE_TEXT = 'Supplier payable does not reconcile against purchases minus payments.';
    END IF;
END //

DELIMITER ;

CALL verify_supplier_ledger();
DROP PROCEDURE verify_supplier_ledger;
