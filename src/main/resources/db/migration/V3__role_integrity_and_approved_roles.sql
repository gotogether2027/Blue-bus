-- Phase 2 corrective migration. Preserve V2 for any environment where it has already run.

ALTER TABLE users
    ALTER COLUMN first_name SET NOT NULL;

ALTER TABLE roles
    ADD CONSTRAINT ck_roles_approved_code_scope CHECK (
        code NOT IN ('SUPER_ADMIN', 'ADMIN', 'CUSTOMER', 'OPERATOR_ADMIN', 'OPERATOR_STAFF')
        OR (code IN ('SUPER_ADMIN', 'ADMIN', 'CUSTOMER') AND scope = 'PLATFORM')
        OR (code IN ('OPERATOR_ADMIN', 'OPERATOR_STAFF') AND scope = 'OPERATOR')
    );

CREATE OR REPLACE FUNCTION prevent_role_scope_membership_mismatch()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.scope <> OLD.scope THEN
        IF NEW.scope <> 'PLATFORM' AND EXISTS (
            SELECT 1 FROM user_roles WHERE role_id = NEW.id
        ) THEN
            RAISE EXCEPTION 'cannot change role scope: role is assigned through user_roles';
        END IF;
        IF NEW.scope <> 'OPERATOR' AND EXISTS (
            SELECT 1 FROM operator_users WHERE role_id = NEW.id
        ) THEN
            RAISE EXCEPTION 'cannot change role scope: role is assigned through operator_users';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_roles_scope_membership_integrity
BEFORE UPDATE OF scope ON roles
FOR EACH ROW EXECUTE FUNCTION prevent_role_scope_membership_mismatch();

INSERT INTO roles (id, code, name, scope, description, created_at, updated_at)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'SUPER_ADMIN', 'Super Administrator', 'PLATFORM', 'Platform-wide super administrator', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('10000000-0000-0000-0000-000000000002', 'ADMIN', 'Administrator', 'PLATFORM', 'Platform administrator', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('10000000-0000-0000-0000-000000000003', 'CUSTOMER', 'Customer', 'PLATFORM', 'Customer account role', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('10000000-0000-0000-0000-000000000004', 'OPERATOR_ADMIN', 'Operator Administrator', 'OPERATOR', 'Operator-scoped administrator', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('10000000-0000-0000-0000-000000000005', 'OPERATOR_STAFF', 'Operator Staff', 'OPERATOR', 'Operator-scoped staff member', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name,
    scope = EXCLUDED.scope,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;
