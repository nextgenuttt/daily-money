-- Phase 6: per-member permission overrides (LLD §4.2, §5). An explicit DENY beats the role's allow.
CREATE TABLE member_permissions (
  org_id     UUID NOT NULL,
  user_id    UUID NOT NULL,
  perm_code  TEXT NOT NULL,
  effect     TEXT NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
  set_by     UUID NOT NULL REFERENCES users(id),
  set_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (org_id, user_id, perm_code),
  FOREIGN KEY (org_id, user_id) REFERENCES organization_members (org_id, user_id)
);
