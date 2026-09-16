-- Phase 9.5F: case-insensitive per-operator uniqueness for seat layout (name, version).
-- PostgreSQL is authoritative; the application ignore-case check remains for friendly 409s.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM seat_layouts
    GROUP BY operator_id, lower(name), version
    HAVING COUNT(*) > 1
  ) THEN
    RAISE EXCEPTION
      'Cannot enforce case-insensitive seat layout uniqueness: duplicate (operator_id, lower(name), version) rows exist';
  END IF;
END $$;

ALTER TABLE seat_layouts
  DROP CONSTRAINT uq_seat_layouts_owner_name_version;

CREATE UNIQUE INDEX ux_seat_layouts_operator_name_version_lower
  ON seat_layouts (operator_id, lower(name), version);
