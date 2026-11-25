# DailyMoneyApp

Personal finance, reminders and family expense app.

| Folder | What |
|---|---|
| `docs/` | Analysis, HLD, LLD, login plan, design & storage decisions |
| `android/` | Android app — Kotlin 2.4, Jetpack Compose, Material 3, Room + SQLCipher, Hilt (AGP 9.5, compileSdk 37.1, targetSdk 36) |
| `backend/` | Spring Boot 4.2 API — Java 17, Firebase ID-token auth, Supabase Postgres, Flyway |
| `firebase/hosting/` | Email action page (verify / reset password) + Android App Link file |

Login is **Firebase Auth** (email + password, Google). **Supabase** is used only for the
Postgres database and file storage, and only the backend talks to it.

## Progress

| Phase | Status |
|---|---|
| 1. Foundation + login (email/password, Google, logout) | Done |
| 2a. Personal money: accounts, categories, income/expense/transfer, search, budgets, reports, Home dashboard | Done |
| 2b. Recurring transactions, subscriptions, savings goals, debts & lending, receipts, CSV/PDF export | Done |
| 3. Reminders, events, calendar, notifications (incl. budget alerts and subscription renewals) | Done |
| 4. To-Do: tasks, subtasks, checklists, repeating tasks, task reminders, quick add | Done |
| 5. Organizations: groups, roles, invitation links, members, ownership (backend + app) | Done (needs a running backend to use) |
| 6. Permission engine: per-member overrides, role editing, permission cache | Done (needs a running backend to use) |
| Hardening: encrypted backup & restore (offered before sign-out), encrypted receipt files, app icon, Google logo, password-manager autofill, libraries on current stable versions | Done |
| 7. Shared expenses & sync: share an expense with a group (everyone or selected people), offline outbox, push/pull with tombstones, FCM wake-ups, group dashboard | Done (needs a running backend to use) |
| 9. Splits & settlements: split equally / by % / by amount / by shares, balances per person, settle up (payer records, receiver confirms), UPI app hand-off, UPI ID in Settings | Done (needs a running backend to use) |
| 8. Approvals: instant / always / conditional (amount, category, person), approvers, several approvals, self-approval switch, re-approval after material edits, approve/reject with reason, history | Done (needs a running backend to use) |
| 10. Messages & notifications: notification centre with bell + badge, approval / share / payment / message notifications (text built on the phone, hidden on lock screen), expense discussions with replies and "seen by", per-phone preferences (categories, muted groups, quiet hours, evening summary) | Done (needs a running backend to use; SMS/email not built) |
| 11. Smart entry (all on the phone): receipt scan with ML Kit fills amount / date / shop / category, bank statement CSV import with column detection, preview and duplicate checks, automatic categories (your rules → your past choices → common merchants), share a bank/UPI SMS into the app to add it | Done (no SMS permission; cloud categorization not built) |
| 12. Release hardening: in-app account deletion (+ web page), privacy policy page, API rate limits and body-size limit, HTTPS-only network config with optional pinning, release signing from local.properties, APK 55 → 12 MB (ML Kit via Play services), startup baseline profile, offline chaos tests for sync (fixed a lost-reply bug), accessibility pass. See docs/06-PLAY-RELEASE.md | Done (App Check, macrobenchmark and TalkBack pass need a device / console) |
| Device test with real Firebase + Supabase | Waiting for project details (see Configuration) |

Personal money data is stored only on the phone (encrypted Room database); signing out deletes it.
Only expenses you choose to share with a group go to the server (Phase 7), and the group sees the
amount, category, date and note, never your account. **Settings → Back up to a file** saves a password-protected copy
(PBKDF2-SHA256 + AES-256-GCM) that **Restore from a backup** brings back; the sign-out dialog offers
"Back up first". Receipt photos are encrypted on the phone (Tink, key in Android Keystore) and are not
part of the backup.

## Configuration (fill in when you have the details)

### Android — `android/local.properties` (not committed)

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
GOOGLE_WEB_CLIENT_ID=xxxxxxxx.apps.googleusercontent.com
AUTH_CONTINUE_URL=https://YOUR_FIREBASE_PROJECT.web.app/auth/done
AUTH_ACTION_HOST=YOUR_FIREBASE_PROJECT.web.app
# Leave empty for R1 (no backend); set when the backend is deployed
API_BASE_URL=
```

Also put `google-services.json` (from the Firebase console) in `android/app/`.

### Backend — environment variables

| Variable | Example |
|---|---|
| `FIREBASE_PROJECT_ID` | `daily-money-123` |
| `FIREBASE_SERVICE_ACCOUNT_FILE` | `/secrets/firebase-service-account.json` |
| `SUPABASE_DB_URL` | `jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require` |
| `SUPABASE_DB_USER` | `postgres.<project-ref>` |
| `SUPABASE_DB_PASSWORD` | (secret) |
| `SUPABASE_URL` | `https://<project-ref>.supabase.co` |
| `SUPABASE_SERVICE_ROLE_KEY` | (secret) |
| `INVITE_LINK_BASE` | `https://YOUR_FIREBASE_PROJECT.web.app/invite/` |

`FIREBASE_SERVICE_ACCOUNT_FILE` also lets the backend send the FCM "sync now" wake-ups. Without it,
other phones still get group changes when they open the app or every 6 hours.

## Build

```bash
cd android && ./gradlew assembleDebug test
cd backend && ./gradlew build
```
