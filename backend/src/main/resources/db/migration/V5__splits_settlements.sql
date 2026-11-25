-- Phase 9: splits and settlements (LLD §4.4, §8).

-- Optional, set by the person themselves so group members can pay them through a UPI app.
ALTER TABLE users ADD COLUMN upi_id TEXT CHECK (length(upi_id) <= 100);

CREATE TABLE expense_splits (
  transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
  user_id        UUID NOT NULL REFERENCES users(id),
  method         TEXT NOT NULL CHECK (method IN ('EQUAL', 'PERCENT', 'AMOUNT', 'SHARES')),
  input_value    NUMERIC(14, 4),                        -- percent, share count or amount as entered
  share_minor    BIGINT NOT NULL CHECK (share_minor >= 0),
  PRIMARY KEY (transaction_id, user_id)
);
CREATE INDEX ix_splits_user ON expense_splits (user_id);

-- Backstop for the service: a split must always add up to its expense, checked at commit so a split
-- can be rewritten row by row inside one transaction.
CREATE FUNCTION check_split_total() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  tid    UUID;
  rows   INT;
  total  BIGINT;
  amount BIGINT;
BEGIN
  IF TG_TABLE_NAME = 'transactions' THEN
    tid := NEW.id;
  ELSIF TG_OP = 'DELETE' THEN
    tid := OLD.transaction_id;
  ELSE
    tid := NEW.transaction_id;
  END IF;
  SELECT count(*), coalesce(sum(share_minor), 0) INTO rows, total FROM expense_splits WHERE transaction_id = tid;
  IF rows = 0 THEN
    RETURN NULL;
  END IF;
  SELECT amount_minor INTO amount FROM transactions WHERE id = tid;
  IF amount IS NOT NULL AND total <> amount THEN
    RAISE EXCEPTION 'Split total % does not match amount % for transaction %', total, amount, tid;
  END IF;
  RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER splits_add_up AFTER INSERT OR UPDATE OR DELETE ON expense_splits
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_split_total();
CREATE CONSTRAINT TRIGGER split_amount_matches AFTER UPDATE OF amount_minor ON transactions
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_split_total();

-- Money paid between two members. The payer records it (REQUESTED) and the receiver confirms, or the
-- receiver records money they got (CONFIRMED straight away). Never confirmed by a UPI app return alone.
CREATE TABLE settlements (
  id           UUID PRIMARY KEY,                       -- generated on the phone
  org_id       UUID NOT NULL REFERENCES organizations(id),
  payer_id     UUID NOT NULL REFERENCES users(id),
  receiver_id  UUID NOT NULL REFERENCES users(id),
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  currency     TEXT NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  method       TEXT NOT NULL CHECK (method IN ('CASH', 'UPI', 'BANK', 'OTHER')),
  status       TEXT NOT NULL CHECK (status IN ('REQUESTED', 'CONFIRMED', 'CANCELLED')),
  note         TEXT CHECK (length(note) <= 200),
  created_by   UUID NOT NULL REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_by   UUID REFERENCES users(id),
  decided_at   TIMESTAMPTZ,
  server_seq   BIGINT NOT NULL,
  CHECK (payer_id <> receiver_id)
);
CREATE INDEX ix_settlements_payer    ON settlements (payer_id, server_seq);
CREATE INDEX ix_settlements_receiver ON settlements (receiver_id, server_seq);

-- Splitting your own expense is the everyday group action, so members get it by default.
INSERT INTO role_permissions (role_id, perm_code)
SELECT id, 'EXPENSE_SPLIT_MANAGE' FROM organization_roles WHERE code = 'MEMBER' AND is_system
ON CONFLICT DO NOTHING;
