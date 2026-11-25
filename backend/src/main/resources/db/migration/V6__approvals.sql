-- Phase 8: configurable expense approval (LLD §4.5, §7, spec §14).

-- No row = INSTANT (the default), so existing groups behave exactly as before.
CREATE TABLE org_approval_policies (
  org_id                       UUID PRIMARY KEY REFERENCES organizations(id),
  mode                         TEXT NOT NULL CHECK (mode IN ('INSTANT', 'REQUIRE_APPROVAL', 'CONDITIONAL')),
  allow_self_approval          BOOLEAN NOT NULL DEFAULT false,
  required_approvals           SMALLINT NOT NULL DEFAULT 1 CHECK (required_approvals BETWEEN 1 AND 5),
  reapprove_on_material_change BOOLEAN NOT NULL DEFAULT true,
  version                      INT NOT NULL DEFAULT 1,
  updated_by                   UUID NOT NULL REFERENCES users(id),
  updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- CONDITIONAL mode: an expense needs approval if any enabled rule matches.
CREATE TABLE approval_rules (
  id        UUID PRIMARY KEY,
  org_id    UUID NOT NULL REFERENCES organizations(id),
  priority  SMALLINT NOT NULL,
  rule_type TEXT NOT NULL CHECK (rule_type IN ('AMOUNT_GTE', 'CATEGORY_IN', 'CREATOR_IN')),
  params    JSONB NOT NULL,       -- {"amountMinor":500000} / {"categories":["Travel"]} / {"userIds":["…"]}
  enabled   BOOLEAN NOT NULL DEFAULT true
);
CREATE INDEX ix_rules_org ON approval_rules (org_id, priority);

CREATE TABLE organization_approvers (
  org_id  UUID NOT NULL,
  user_id UUID NOT NULL,
  PRIMARY KEY (org_id, user_id),
  FOREIGN KEY (org_id, user_id) REFERENCES organization_members (org_id, user_id)
);

-- Append-only. Decisions are tied to the version they were made on, so an edit starts over.
CREATE TABLE expense_approvals (
  id             UUID PRIMARY KEY,
  transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
  txn_version    INT NOT NULL,
  approver_id    UUID NOT NULL REFERENCES users(id),
  decision       TEXT NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
  reason         TEXT CHECK (length(reason) <= 300),
  decided_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (transaction_id, txn_version, approver_id),
  CHECK (decision = 'APPROVED' OR (reason IS NOT NULL AND length(trim(reason)) > 0))
);

-- Owners can approve by default so turning approvals on doesn't need a permission change first.
-- Admins stay off (configurable, LLD §5.3).
INSERT INTO role_permissions (role_id, perm_code)
SELECT id, 'EXPENSE_APPROVE' FROM organization_roles WHERE code = 'OWNER' AND is_system
ON CONFLICT DO NOTHING;
