-- Syllabi the extractor read and found no grading scheme in. Written by the syllabus pipeline
-- (tools/syllabus-pipeline), never by the backend.
--
-- Without this, a document with no scheme is indistinguishable from one never extracted, so every
-- run sends it to Gemini again, three times, and gets the same answer. A row here keyed to the
-- source text's sha256 makes the pipeline skip it until the syllabus text changes, at which point
-- the hashes stop matching and it is extracted again like any other edited source.

CREATE TABLE syllabus_no_scheme (
    doc_code character varying(128) NOT NULL,
    source_text_sha256 character varying(64) NOT NULL,
    reason text,
    model character varying(64),
    extract_run_id character varying(64),
    recorded_at timestamp with time zone NOT NULL,
    CONSTRAINT syllabus_no_scheme_pkey PRIMARY KEY (doc_code)
);
