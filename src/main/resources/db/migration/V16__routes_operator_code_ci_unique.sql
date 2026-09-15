-- Phase 9.5B: case-insensitive per-operator uniqueness for route codes.
-- PostgreSQL is authoritative; the application ignore-case check remains for friendly 409s.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM routes
    GROUP BY operator_id, lower(code)
    HAVING COUNT(*) > 1
  ) THEN
    RAISE EXCEPTION
      'Cannot enforce case-insensitive route code uniqueness: duplicate (operator_id, lower(code)) rows exist';
  END IF;
END $$;

ALTER TABLE routes
  DROP CONSTRAINT uq_routes_operator_code;

CREATE UNIQUE INDEX ux_routes_operator_code_lower
  ON routes (operator_id, lower(code));
