-- Phase 7: shared expenses and sync (LLD §4.3, §4.9, §6). Personal money stays on the phone, so this
-- table only holds expenses shared with a group.

-- Global change counter. Every write takes the next value, so "changes after cursor N" is one range scan.
CREATE SEQUENCE sync_seq;

CREATE TABLE transactions (
  id              UUID PRIMARY KEY,                       -- generated on the phone
  org_id          UUID NOT NULL REFERENCES organizations(id),
  creator_id      UUID NOT NULL REFERENCES users(id),
  type            TEXT NOT NULL CHECK (type IN ('DEBIT', 'CREDIT')),
  amount_minor    BIGINT NOT NULL CHECK (amount_minor > 0),
  currency        TEXT NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  -- Categories live on each phone, so the label travels with the expense.
  category_name   TEXT CHECK (length(category_name) <= 60),
  category_icon   TEXT CHECK (length(category_icon) <= 40),
  occurred_at     TIMESTAMPTZ NOT NULL,
  note            TEXT CHECK (length(note) <= 200),
  payment_method  TEXT CHECK (payment_method IN ('CASH', 'UPI', 'CARD', 'BANK_TRANSFER', 'WALLET', 'OTHER')),
  visibility      TEXT NOT NULL CHECK (visibility IN ('ORGANIZATION', 'SELECTED_MEMBERS')),
  approval_status TEXT NOT NULL DEFAULT 'APPROVED' CHECK (approval_status IN ('APPROVED', 'PENDING_APPROVAL', 'REJECTED')),
  version         INT NOT NULL DEFAULT 1,
  server_seq      BIGINT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at      TIMESTAMPTZ
);
CREATE INDEX ix_txn_org_seq  ON transactions (org_id, server_seq);
CREATE INDEX ix_txn_org_date ON transactions (org_id, occurred_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE expense_allowed_members (
  transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
  user_id        UUID NOT NULL REFERENCES users(id),
  PRIMARY KEY (transaction_id, user_id)
);
CREATE INDEX ix_eam_user ON expense_allowed_members (user_id);

-- Tombstones for one person who could see a row and no longer can (visibility narrowed), LLD §6.4.
CREATE TABLE sync_revocations (
  seq         BIGINT PRIMARY KEY,
  target_user UUID NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id   UUID NOT NULL
);
CREATE INDEX ix_revocations_user ON sync_revocations (target_user, seq);

-- Replayed pushes (same op id) get the stored answer instead of applying twice.
CREATE TABLE idempotency_keys (
  user_id    UUID NOT NULL,
  key        UUID NOT NULL,
  response   TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, key)
);
