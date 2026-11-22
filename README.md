# DailyMoneyApp

Personal finance, reminders and family expense app.

| Folder | What |
|---|---|
| `docs/` | Analysis, HLD, LLD, login plan, design & storage decisions |
| `android/` | Android app — Kotlin, Jetpack Compose, Material 3, Room + SQLCipher, Hilt |
| `backend/` | Spring Boot API — Java 17, Firebase ID-token auth, Supabase Postgres, Flyway |
| `firebase/hosting/` | Email action page (verify / reset password) + Android App Link file |

Login is **Firebase Auth** (email + password, Google). **Supabase** is used only for the
Postgres database and file storage, and only the backend talks to it.

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

## Build

```bash
cd android && ./gradlew assembleDebug test
cd backend && ./gradlew build
```
