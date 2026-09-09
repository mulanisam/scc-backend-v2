# Sales Module Upgrade with Ledger Management System

## 🎯 Overview

This upgrade adds a comprehensive **Ledger Management System** to your poultry management application with the following key features:

### ✨ New Features

1. **Customer Ledger System** - Complete transaction history tracking
2. **Single Sale Entry** - Simplified individual sale recording
3. **Payment Management** - Standalone payment entry system
4. **Backdate Handling** - Automatic recalculation of all balances when backdated entries are added
5. **Credit Limit Management** - Optional credit limit per customer

---

## 📋 Database Changes

### New Tables Created

1. **`customer_ledger`** - Transaction log for all customer activities
   - Tracks every sale, payment, credit note, debit note
   - Maintains running balance
   - Supports backdate detection and handling

2. **`customer_payment`** - Payment tracking
   - Records all customer payments
   - Multiple payment modes supported
   - Soft delete functionality

### Modified Tables

**`customer`** table - Added fields:
- `credit_limit_enabled` (boolean, default: false)
- `credit_limit` (double, nullable)

---

## 🔌 New API Endpoints

### Sales APIs

#### 1. Single Sale Entry (NEW)
```http
POST /user/sales/single
Content-Type: application/json

{
  "date": "2024-01-21",
  "customerId": 1,
  "routeId": 1,
  "vehicleId": 1,
  "driverId": 1,
  "kilograms": 50.5,
  "rate": 180.0,
  "birds": 100,
  "amount": 9090,
  "payment": 5000,
  "paymentMode": "CASH",
  "description": "Regular sale"
}
```

**Features:**
- Automatic ledger entry creation
- Balance calculation
- Credit limit validation (if enabled)
- Backdate detection and handling

#### 2. Bulk Sale Entry (EXISTING - Enhanced)
```http
POST /user/sales/bulk
```
- Now creates ledger entries for all sales
- Maintains backward compatibility

---

### Payment APIs (NEW)

#### 1. Create Payment
```http
POST /user/payments
Content-Type: application/json

{
  "customerId": 1,
  "paymentDate": "2024-01-21",
  "amount": 5000.0,
  "paymentMode": "UPI",
  "transactionReference": "UPI123456789",
  "remarks": "Payment received",
  "receivedBy": "Admin"
}
```

#### 2. Get Customer Payments
```http
GET /user/payments/customer/{customerId}
```

#### 3. Get Payments by Date Range
```http
GET /user/payments/date-range?startDate=2024-01-01&endDate=2024-01-31
```

#### 4. Get Payment by ID
```http
GET /user/payments/{paymentId}
```

#### 5. Delete Payment
```http
DELETE /user/payments/{paymentId}
```
- Soft delete with automatic balance recalculation

---

### Ledger APIs (NEW)

#### 1. Get Customer Ledger
```http
GET /user/ledger/customer/{customerId}
GET /user/ledger/customer/{customerId}?startDate=2024-01-01&endDate=2024-01-31
```

**Response:**
```json
[
  {
    "id": 1,
    "customerId": 1,
    "customerName": "John Doe",
    "transactionDate": "2024-01-20",
    "transactionType": "SALE",
    "referenceType": "SALE",
    "referenceId": 123,
    "debitAmount": 9090.0,
    "creditAmount": 5000.0,
    "runningBalance": 4090.0,
    "description": "Sale - 100 birds, 50.5 kg",
    "paymentMode": "CASH",
    "createdAt": "2024-01-20T10:30:00",
    "isBackdated": false
  }
]
```

---

### Migration APIs (ADMIN)

#### 1. Migrate Existing Data
```http
POST /admin/migration/ledger
```

**What it does:**
- Creates opening balance entries for all customers
- Migrates all existing sales to ledger
- Recalculates running balances
- **⚠️ Run only once after deployment**

**Response:**
```json
{
  "success": true,
  "message": "Migration completed successfully",
  "customersProcessed": 150,
  "salesMigrated": 5420,
  "ledgerEntriesCreated": 5570
}
```

#### 2. Check Migration Status
```http
GET /admin/migration/status
```

---

## 🔄 How Backdate Handling Works

### Scenario:
1. Today is January 25, 2024
2. Customer has transactions on Jan 20, 22, 23, 24, 25
3. You insert a new sale dated **January 21** (backdated)

### What Happens:
1. System detects the backdated entry
2. Automatically recalculates running balance for **ALL transactions from Jan 21 onwards**
3. Updates customer's current balance
4. Maintains data integrity across all records

### Code Flow:
```java
// When creating a sale/payment
if (transactionDate.isBefore(LocalDate.now())) {
    ledgerEntry.setBackdated(true);
    // Trigger recalculation
    ledgerService.recalculateBalancesFromDate(customer, transactionDate);
}
```

---

## 💳 Credit Limit Management

### Enable Credit Limit for a Customer:
```json
{
  "creditLimitEnabled": true,
  "creditLimit": 50000.0
}
```

### Validation:
- System checks before allowing new sales
- If `currentBalance + newSaleAmount > creditLimit`, transaction is rejected
- Optional feature - disabled by default

---

## 🚀 Deployment Steps

### 1. Backup Database
```sql
mysqldump -u root -p poultry_db > backup_before_upgrade.sql
```

### 2. Deploy Updated Code
- Replace backend files with upgraded version
- Restart application

### 3. Database Auto-Migration
- Spring Boot will automatically create new tables
- Existing data remains intact

### 4. Run Data Migration (ONE TIME ONLY)
```http
POST /admin/migration/ledger
```

### 5. Verify Migration
```http
GET /admin/migration/status
```

Expected output:
```
Ledger Entries: 5570, Sales: 5420, Customers: 150
```

### 6. Test
- Create a new sale using `/user/sales/single`
- Create a payment using `/user/payments`
- View ledger using `/user/ledger/customer/{id}`
- Test backdate entry (use past date)
- Verify balances are correct

---

## 📊 Transaction Types in Ledger

| Type | Debit | Credit | Description |
|------|-------|--------|-------------|
| `OPENING_BALANCE` | Balance | 0 | Initial customer balance |
| `SALE` | Amount | Payment | Sale with optional payment |
| `PAYMENT` | 0 | Amount | Standalone payment |
| `CREDIT_NOTE` | 0 | Amount | Refund to customer |
| `DEBIT_NOTE` | Amount | 0 | Additional charge |
| `ADJUSTMENT` | +/- | +/- | Manual adjustment |

**Formula:**
```
Running Balance = Previous Balance + Debit - Credit
```

---

## 🔍 Key Classes

### Entities
- `CustomerLedger.java` - Ledger entry entity
- `CustomerPayment.java` - Payment entity
- `Customer.java` - Updated with credit limit fields

### Services
- `LedgerService.java` / `LedgerServiceImpl.java` - Core ledger logic
- `PaymentService.java` / `PaymentServiceImpl.java` - Payment management
- `LedgerMigrationService.java` - Data migration
- `SalesServiceImpl.java` - Enhanced with single sale entry

### Controllers
- `SaleController.java` - Added single sale endpoint
- `PaymentController.java` - Payment APIs
- `LedgerController.java` - Ledger viewing APIs
- `MigrationController.java` - Migration endpoints

### Repositories
- `CustomerLedgerRepository.java`
- `CustomerPaymentRepository.java`
- `SaleRepository.java` - Added method for migration

---

## ⚠️ Important Notes

### Backward Compatibility
- ✅ All existing APIs continue to work
- ✅ Existing frontend code works without changes
- ✅ Existing data is preserved
- ✅ Bulk sale entry enhanced but API unchanged

### Data Integrity
- All balance calculations are transactional
- Backdate handling ensures consistency
- Soft delete prevents data loss
- Audit trail with timestamps

### Performance
- Indexed queries on transaction dates
- Efficient batch recalculation
- Lazy loading where appropriate

---

## 📱 Frontend Integration Guide

### 1. Single Sale Entry Form
```javascript
const createSingleSale = async (saleData) => {
  const response = await axios.post(
    `${API_BASE_URL}/user/sales/single`,
    saleData,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  return response.data;
};
```

### 2. Payment Entry Form
```javascript
const createPayment = async (paymentData) => {
  const response = await axios.post(
    `${API_BASE_URL}/user/payments`,
    paymentData,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  return response.data;
};
```

### 3. Customer Ledger View
```javascript
const getCustomerLedger = async (customerId, startDate, endDate) => {
  const params = new URLSearchParams();
  if (startDate) params.append('startDate', startDate);
  if (endDate) params.append('endDate', endDate);
  
  const response = await axios.get(
    `${API_BASE_URL}/user/ledger/customer/${customerId}?${params}`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  return response.data;
};
```

---

## 🐛 Troubleshooting

### Issue: Migration fails with "Ledger already contains data"
**Solution:** Migration has already been run. Check with `GET /admin/migration/status`

### Issue: Balances don't match after migration
**Solution:** Run recalculation:
```java
// For specific customer
ledgerService.recalculateAllBalances(customer);

// Or use migration endpoint again (it will skip if data exists)
```

### Issue: Credit limit error when creating sale
**Solution:** Either:
1. Increase customer's credit limit
2. Receive payment first
3. Disable credit limit for that customer

---

## 📈 Future Enhancements (Not Included)

- Customer statement PDF generation
- Aging analysis (30/60/90 days)
- Payment reminders
- SMS integration for payment confirmations
- Credit limit alerts
- Advanced reports

---

## 🔒 Security Considerations

- All new endpoints use existing authentication
- Admin endpoints protected by role-based access
- Soft deletes prevent accidental data loss
- Transaction logs for audit trail

---

## 📞 Support

For issues or questions:
1. Check logs: `/var/log/supervisor/backend.*.log`
2. Verify database migrations completed
3. Test with Postman/curl first
4. Check browser console for frontend errors

---

## ✅ Testing Checklist

- [ ] Backend starts without errors
- [ ] Database tables created (customer_ledger, customer_payment)
- [ ] Migration runs successfully
- [ ] Single sale creation works
- [ ] Payment entry works
- [ ] Ledger view shows correct data
- [ ] Backdate entry triggers recalculation
- [ ] Balances match across sale and ledger
- [ ] Credit limit validation works
- [ ] Existing bulk sale entry still works

---

**Version:** 2.0  
**Date:** January 2024  
**Compatibility:** Spring Boot 3.2.5+, Java 17+, MySQL 8.0+
