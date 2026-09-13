# Sohel Chicken Centre — backend

Spring Boot API for a poultry trading business: retail sales by route, wholesale
trading by party, purchases from suppliers, a customer/supplier ledger, and
WhatsApp/SMS messaging for daily balances, receipts and weekly statements.

Java 17, Spring Boot 3.2.5, Hibernate 6.4.4, MySQL 8, Flyway-managed schema.

## Running it

```bash
cp .env.example .env   # fill in the real values - see the comments in that file
ENV=dev ./mvnw spring-boot:run       # bash
$env:ENV = "dev"; .\mvnw.cmd spring-boot:run   # PowerShell
```

`ENV` has no default and the app refuses to start without it — a wrong guess here
means writing real trading into the test schema, or test data into the live one,
with no error to catch it. Three values:

| `ENV` | Database |
|---|---|
| `dev` | `poultry_db_dev` |
| `test` | `poultry_db_test` |
| `prod` | `poultry_db` — the live one |

Flyway migrates the schema on startup; nothing manual is needed beyond a MySQL
instance existing with that database name.

## Tests

```bash
./mvnw test
```

## What's in `docs/`

- **`running-the-stack.md`** — running the whole stack (this + the frontend)
  locally, on the same LAN, and what changes when it moves to the cloud
- **`whatsapp-templates.md`** — every WhatsApp template on the Fast2SMS account,
  what each one takes, and what happens while one is waiting for approval
- **`supplier-payables.md`** — the supplier ledger: why it exists, the bug it
  replaced, and how the payable is derived rather than stored

## Before this goes anywhere it's reachable from outside your own machine

Rotate the Fast2SMS API key. It was a plaintext constant in `SendSmsService.java`
early in this project's history and is now in `.env` where it belongs, but moving
it doesn't undo a key that was already committed — see `.env.example` for the
detail. Generate a fresh one in the Fast2SMS dashboard and update `.env`
(and wherever production's environment variables are set) before relying on this
in a setting where the git history could be read by anyone who shouldn't have that
key.
