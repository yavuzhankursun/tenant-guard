-- No index on tenant_id: small tables + seq scan is the worst case for the observer.
CREATE TABLE invoice (
    id        bigserial PRIMARY KEY,
    tenant_id text    NOT NULL,
    number    text    NOT NULL,
    amount    numeric NOT NULL
);

-- Denormalized tenant_id, but queries filter it through the parent invoice.
CREATE TABLE invoice_line (
    id          bigserial PRIMARY KEY,
    tenant_id   text   NOT NULL,
    invoice_id  bigint NOT NULL REFERENCES invoice (id),
    description text   NOT NULL
);

-- An app that already isolates with its own RLS policy.
CREATE TABLE legacy_doc (
    id        bigserial PRIMARY KEY,
    tenant_id text NOT NULL,
    title     text NOT NULL
);
ALTER TABLE legacy_doc ENABLE ROW LEVEL SECURITY;
CREATE POLICY legacy_isolation ON legacy_doc USING (tenant_id = current_setting('app.tenant', true));
