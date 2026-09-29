ALTER TABLE t_document
    ADD COLUMN document_type VARCHAR(30) NULL,
    ADD COLUMN service_name VARCHAR(100) NULL;

UPDATE t_document SET document_type = 'GENERAL' WHERE document_type IS NULL;
