-- Phase 9.5A: case-insensitive global uniqueness for bus registration numbers.
-- PostgreSQL is authoritative; the application ignore-case check remains for friendly 409s.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM buses
    GROUP BY lower(registration_number)
    HAVING COUNT(*) > 1
  ) THEN
    RAISE EXCEPTION
      'Cannot enforce case-insensitive bus registration uniqueness: duplicate lower(registration_number) rows exist';
  END IF;
END $$;

ALTER TABLE buses
  DROP CONSTRAINT uq_buses_registration_number;

CREATE UNIQUE INDEX ux_buses_registration_number_lower
  ON buses (lower(registration_number));
