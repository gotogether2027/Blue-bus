-- Position metadata for the operator seat-layout builder.
-- Existing layouts keep layout_type CUSTOM and markers_json [].
-- Existing seat rows are not rewritten; missing attributes stay {}.

ALTER TABLE seat_layouts
    ADD COLUMN layout_type VARCHAR(30) NOT NULL DEFAULT 'CUSTOM';

ALTER TABLE seat_layouts
    ADD CONSTRAINT ck_seat_layouts_layout_type
        CHECK (layout_type IN ('SEATER', 'SLEEPER', 'SEATER_SLEEPER', 'CUSTOM'));

ALTER TABLE seat_layouts
    ADD COLUMN markers_json JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE seat_layouts
    ADD CONSTRAINT ck_seat_layouts_markers_array
        CHECK (jsonb_typeof(markers_json) = 'array');
