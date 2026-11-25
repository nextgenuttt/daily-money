# 06 — Release hardening & Google Play (Phase 12)

What Phase 12 changed, what to fill in on the Play Console, and what still needs a device or an account.

## 1. Release checklist

| Step | Where | Status |
|---|---|---|
| Upload key: `keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload` | your computer, keep outside git | to do |
| `local.properties`: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `VERSION_CODE`, `VERSION_NAME` | android/ | to do |
| `./gradlew bundleRelease` → upload the `.aab`; turn on **Play App Signing** | Play Console | to do |
| `APPLICATION_ID` final (can't change after the first upload) | local.properties | to do |
| Replace `SUPPORT_EMAIL`, `OWNER_NAME`, `EFFECTIVE_DATE` in `firebase/hosting/public/privacy` and `/delete-account`, then `firebase deploy --only hosting` | Firebase | to do |
| Privacy policy URL `https://<host>/privacy`; account deletion URL `https://<host>/delete-account` | Play Console → App content | to do |
| Backend env: `RATE_LIMIT_PER_MINUTE`, `RATE_LIMIT_BURST` (defaults 240 / 120); leave `API_DOCS_ENABLED` unset in production | server | defaults OK |
| Optional: certificate pins in `API_CERT_PINS` (comma-separated `sha256/…`, always include a backup key) | local.properties | optional |

## 2. Data Safety form (answers)

- **Data collected:** Personal info → *Name*, *Email address* (account; required). Financial info → *Other
  financial info* (expenses you share with a group, settlements, UPI ID; optional, only if you use groups).
  Messages → *Other in-app messages* (expense discussions; optional). App activity → none. Device or other IDs →
  *Device or other IDs* (random device ID + push token, for sign-in management and notifications).
- **Not collected:** location, contacts, photos/files (receipts and statements stay on the phone; ML Kit runs on
  the device), SMS (only text the user shares in), health, web history, precise financial accounts.
- **Shared with third parties:** none (Firebase and Supabase are service providers processing on our behalf).
- **Purpose:** App functionality, Account management. No advertising or analytics.
- **Encrypted in transit:** yes. **Users can request deletion:** yes (in app + web link).
- **Personal money data** (accounts, transactions, budgets, reminders, tasks) never leaves the phone → not
  "collected" under Play's definition.

## 3. Permissions and why

| Permission | Why | Play notes |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Sign-in, groups, sync | normal |
| `POST_NOTIFICATIONS` | Reminders, budget alerts, group notifications | asked at runtime |
| `SCHEDULE_EXACT_ALARM` | Reminders at the exact time | denied by default on Android 14+; the app falls back to inexact alarms. Do **not** switch to `USE_EXACT_ALARM` (reserved for alarm/calendar apps) |
| `RECEIVE_BOOT_COMPLETED` | Re-arm reminders after a restart | normal |
| `WAKE_LOCK`, `FOREGROUND_SERVICE` | WorkManager (library) | no foreground service types are used |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | Credential Manager (library) | normal |
| No `READ_SMS` / `RECEIVE_SMS`, no `CAMERA`, no storage permissions | bank SMS by share, photos through the system camera/picker, files through the system file picker | nothing to declare |

Other declarations: **Financial features** → "personal finance / budgeting, no loans, no payments processed in
the app" (UPI hand-off opens the user's own UPI app). **Target audience:** 18+. **Ads:** no.

## 4. What Phase 12 hardened

**Security review (backend + app)**
- Per-user rate limit (token bucket, 429 + `Retry-After`) after authentication; request bodies over 1 MB refused
  (413); sync batches already capped (100 ops, 500 rows per pull).
- OpenAPI/Swagger off unless `API_DOCS_ENABLED=true`.
- SQL: every query is parameterised (column lists are constants). Errors return problem details without stack traces.
- App: HTTPS only with system CAs only (`network_security_config.xml`; debug builds may use plain HTTP to
  `10.0.2.2`/`localhost`), optional certificate pinning, HTTP logging only in debug with `Authorization` redacted,
  `allowBackup=false` + data-extraction rules, all receivers but the reschedule one unexported (it only re-arms
  alarms), shared text capped at 1,000 characters and only pre-fills a form.
- Account deletion (Play requirement): Settings → Delete account. The server leaves every group (an owner must
  hand over groups that still have members; groups they're alone in are deleted), deletes devices and
  notifications, clears name/email/phone/UPI ("Former member") and deletes the Firebase user; the phone is then
  wiped like a sign-out. Without a backend the Firebase user is deleted from the phone ("sign in again" if
  Firebase asks for a recent login).

**Performance**
- ML Kit moved to the Google Play services model (`play-services-mlkit-text-recognition`, model fetched at
  install via `com.google.mlkit.vision.DEPENDENCIES=ocr`): the release APK went from **55 MB to 12 MB**
  (all ABIs); a per-device download from the App Bundle is about 7–8 MB. HLD target < 15 MB: met.
- Startup baseline profile (`app/src/main/baseline-prof.txt`, installed by ProfileInstaller) for the app shell,
  Home, session, database and design system. For exact rules and the < 1 s cold-start check, add a
  Macrobenchmark module and run `BaselineProfileRule` on a device (not done: needs a device or emulator).

**Offline chaos tests** (`core/data/.../SyncChaosTest`)
- 25 random scenarios × 40 steps: creates, edits, deletes and un-shares while pushes/pulls fail before reaching
  the server or lose their reply. After the network returns: empty outbox, phone == server, versions match, no
  op applied twice.
- Found and fixed a real bug: if the reply to a share was lost and the user then deleted the expense (or made it
  personal) before the next sync, the phone dropped the change and the group kept the expense. Rows now remember
  they were sent (`pushAttempted`, Room v11) and send a delete in that case.

**Accessibility**
- Every icon-only button has a label; charts have text descriptions; amounts carry a sign, not just colour;
  touch targets stay at 48 dp (Material's minimum interactive size). Still to do on a device: TalkBack pass
  and 200 % font size check on each screen.

## 5. Not done (needs an account, a device or a decision)

- **Firebase App Check (Play Integrity)** on the backend: needs the Firebase console and the Play Console app.
- Macrobenchmark + generated baseline profile, TalkBack/large-font pass, real-device offline test.
- An external penetration test (this phase is a code-level review).
