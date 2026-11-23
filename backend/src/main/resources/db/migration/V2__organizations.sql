-- Phase 5: organizations, roles, membership, invitations, audit (LLD §4.1, §4.2, §4.9).

CREATE TABLE organizations (
  id          UUID PRIMARY KEY,
  name        TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
  kind        TEXT NOT NULL CHECK (kind IN ('FAMILY', 'FRIENDS', 'OFFICE', 'TRAVEL', 'OTHER')),
  currency    TEXT NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  created_by  UUID NOT NULL REFERENCES users(id),
  version     INT NOT NULL DEFAULT 1,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at  TIMESTAMPTZ
);

CREATE TABLE organization_roles (
  id         UUID PRIMARY KEY,
  org_id     UUID NOT NULL REFERENCES organizations(id),
  code       TEXT NOT NULL,
  name       TEXT NOT NULL,
  is_system  BOOLEAN NOT NULL DEFAULT false,
  UNIQUE (org_id, code)
);

CREATE TABLE role_permissions (
  role_id    UUID NOT NULL REFERENCES organization_roles(id) ON DELETE CASCADE,
  perm_code  TEXT NOT NULL,
  PRIMARY KEY (role_id, perm_code)
);

CREATE TABLE organization_members (
  org_id       UUID NOT NULL REFERENCES organizations(id),
  user_id      UUID NOT NULL REFERENCES users(id),
  role_id      UUID NOT NULL REFERENCES organization_roles(id),
  status       TEXT NOT NULL CHECK (status IN ('ACTIVE', 'REMOVED', 'LEFT')),
  perm_version INT NOT NULL DEFAULT 1,
  joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  removed_at   TIMESTAMPTZ,
  PRIMARY KEY (org_id, user_id)
);
CREATE INDEX ix_members_user_active ON organization_members (user_id) WHERE status = 'ACTIVE';

CREATE TABLE organization_invitations (
  id            UUID PRIMARY KEY,
  org_id        UUID NOT NULL REFERENCES organizations(id),
  invited_by    UUID NOT NULL REFERENCES users(id),
  invitee_email TEXT,
  role_id       UUID NOT NULL REFERENCES organization_roles(id),
  token_hash    BYTEA NOT NULL UNIQUE,
  max_uses      INT NOT NULL CHECK (max_uses BETWEEN 1 AND 50),
  uses          INT NOT NULL DEFAULT 0,
  status        TEXT NOT NULL CHECK (status IN ('PENDING', 'USED_UP', 'DECLINED', 'REVOKED')),
  expires_at    TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (uses <= max_uses)
);
CREATE INDEX ix_invitations_org ON organization_invitations (org_id, status);

-- Append-only: the application role must never update or delete rows here.
CREATE TABLE audit_log (
  id          BIGSERIAL PRIMARY KEY,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_id    UUID NOT NULL,
  org_id      UUID,
  entity_type TEXT NOT NULL,
  entity_id   TEXT NOT NULL,
  action      TEXT NOT NULL,
  detail      TEXT
);
CREATE INDEX ix_audit_org ON audit_log (org_id, occurred_at DESC);
