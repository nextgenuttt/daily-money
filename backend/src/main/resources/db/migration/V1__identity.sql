-- Schema `app` is created by Flyway and is not exposed through Supabase's Data API.

CREATE TABLE users (
  id                 UUID PRIMARY KEY,
  firebase_uid       TEXT NOT NULL UNIQUE,
  email              TEXT,
  email_verified     BOOLEAN NOT NULL DEFAULT false,
  auth_providers     TEXT[] NOT NULL DEFAULT '{}',
  display_name       TEXT NOT NULL,
  phone_e164         TEXT UNIQUE,
  default_currency   CHAR(3) NOT NULL DEFAULT 'INR',
  time_zone          TEXT NOT NULL DEFAULT 'Asia/Kolkata',
  status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),
  tokens_valid_after TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at         TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email)) WHERE email IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE devices (
  id           UUID PRIMARY KEY,
  user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform     TEXT NOT NULL DEFAULT 'ANDROID',
  device_name  TEXT,
  app_version  TEXT,
  fcm_token    TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_devices_user ON devices (user_id);
