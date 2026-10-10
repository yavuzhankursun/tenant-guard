-- Leak corpus schema. Applied only by the corpus tests (their own container, extra Flyway location):
-- in db/migration it would add tenant tables to the shared container and change what
-- TableDiscovery finds there. No index on tenant_id: seq scans are the observer's worst case.
CREATE TABLE corpus_invoice (
    id        bigserial     PRIMARY KEY,
    tenant_id text          NOT NULL,
    name      text          NOT NULL,
    status    text          NOT NULL,
    amount    numeric(12,2) NOT NULL
);

-- Denormalized tenant_id, but queries filter it through the parent invoice.
CREATE TABLE corpus_line (
    id          bigserial PRIMARY KEY,
    tenant_id   text   NOT NULL,
    invoice_id  bigint NOT NULL REFERENCES corpus_invoice (id),
    description text   NOT NULL
);

-- Default view: base-table RLS is checked as the view owner (here the table owner).
CREATE VIEW corpus_invoice_view AS
    SELECT id, tenant_id, name, amount FROM corpus_invoice;

-- PG15+: base-table RLS is checked as the querying role.
CREATE VIEW corpus_invoice_invoker_view WITH (security_invoker = true) AS
    SELECT id, tenant_id, name, amount FROM corpus_invoice;

-- SECURITY INVOKER (the default): the body runs as the caller.
CREATE FUNCTION corpus_total() RETURNS numeric LANGUAGE sql STABLE AS $$
    SELECT coalesce(sum(amount), 0) FROM corpus_invoice
$$;

-- SECURITY DEFINER: the body runs as the function owner (here the table owner).
CREATE FUNCTION corpus_total_definer() RETURNS numeric LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public AS $$
    SELECT coalesce(sum(amount), 0) FROM corpus_invoice
$$;
