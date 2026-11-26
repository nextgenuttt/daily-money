#!/usr/bin/env bash
# Deploys the backend to Google Cloud Run (Tokyo, next to the Supabase database).
# Needs: gcloud logged in as the project owner, billing on, backend/.env with SUPABASE_* values.
# Safe to run again: each step skips what already exists. The database password goes to Secret Manager,
# never into the image; Firebase uses the service's own identity, so no key file is uploaded.
set -euo pipefail

PROJECT=dailymonyapp
REGION=asia-northeast1
SERVICE=dailymoney-api
SA_NAME=dailymoney-backend
SA="$SA_NAME@$PROJECT.iam.gserviceaccount.com"
SECRET=supabase-db-password
cd "$(dirname "$0")/.."

env_value() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }

echo "1/5 Turning on the Google Cloud services we use"
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com firebase.googleapis.com fcm.googleapis.com identitytoolkit.googleapis.com --project "$PROJECT"

echo "2/5 Service identity with only what it needs"
gcloud iam service-accounts describe "$SA" --project "$PROJECT" >/dev/null 2>&1 \
  || gcloud iam service-accounts create "$SA_NAME" --display-name "Daily Money backend" --project "$PROJECT"
for role in roles/secretmanager.secretAccessor roles/firebaseauth.admin roles/firebasecloudmessaging.admin; do
  gcloud projects add-iam-policy-binding "$PROJECT" --member "serviceAccount:$SA" --role "$role" --condition None --quiet >/dev/null
done

echo "3/5 Database password into Secret Manager"
if gcloud secrets describe "$SECRET" --project "$PROJECT" >/dev/null 2>&1; then
  printf '%s' "$(env_value SUPABASE_DB_PASSWORD)" | gcloud secrets versions add "$SECRET" --data-file=- --project "$PROJECT" >/dev/null
else
  printf '%s' "$(env_value SUPABASE_DB_PASSWORD)" | gcloud secrets create "$SECRET" --data-file=- --replication-policy automatic --project "$PROJECT" >/dev/null
fi

echo "4/5 Building and deploying (about 5 minutes the first time)"
gcloud run deploy "$SERVICE" \
  --project "$PROJECT" --region "$REGION" --source . \
  --service-account "$SA" \
  --allow-unauthenticated \
  --min-instances 0 --max-instances 1 --concurrency 40 \
  --cpu 1 --memory 1Gi --cpu-boost --timeout 60 \
  --set-env-vars "FIREBASE_PROJECT_ID=$PROJECT,FIREBASE_SERVICE_ACCOUNT_FILE=google,SUPABASE_DB_URL=$(env_value SUPABASE_DB_URL),SUPABASE_DB_USER=$(env_value SUPABASE_DB_USER),SUPABASE_URL=$(env_value SUPABASE_URL),INVITE_LINK_BASE=$(env_value INVITE_LINK_BASE),DB_POOL_SIZE=5" \
  --set-secrets "SUPABASE_DB_PASSWORD=$SECRET:latest" \
  --quiet

echo "5/5 Done"
URL=$(gcloud run services describe "$SERVICE" --project "$PROJECT" --region "$REGION" --format 'value(status.url)')
echo "Backend URL: $URL"
curl -s "$URL/actuator/health" || true
echo
