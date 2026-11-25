-- Phase 10: in-app notifications and expense messages (LLD §4.7, §4.8, §10).

-- One row per person. The phone builds the text from type + params, so a push never carries content.
CREATE TABLE notifications (
  id          UUID PRIMARY KEY,
  user_id     UUID NOT NULL REFERENCES users(id),
  org_id      UUID REFERENCES organizations(id),
  type        TEXT NOT NULL,
  entity_type TEXT,
  entity_id   UUID,
  params      JSONB NOT NULL DEFAULT '{}',
  dedupe_key  TEXT NOT NULL,
  read_at     TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  server_seq  BIGINT NOT NULL,
  UNIQUE (user_id, dedupe_key)
);
CREATE INDEX ix_notifications_user_seq ON notifications (user_id, server_seq);

CREATE TABLE expense_messages (
  id             UUID PRIMARY KEY,                     -- generated on the phone, so a retry doesn't post twice
  transaction_id UUID NOT NULL REFERENCES transactions(id),
  org_id         UUID NOT NULL REFERENCES organizations(id),
  sender_id      UUID NOT NULL REFERENCES users(id),
  parent_id      UUID REFERENCES expense_messages(id),
  body           TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 2000),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at     TIMESTAMPTZ
);
CREATE INDEX ix_messages_txn ON expense_messages (transaction_id, created_at);

CREATE TABLE message_receipts (
  message_id UUID NOT NULL REFERENCES expense_messages(id),
  user_id    UUID NOT NULL REFERENCES users(id),
  read_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (message_id, user_id)
);
