create table audit_events (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    invoice_id uuid not null,
    field_name varchar(64) not null,
    old_value text,
    new_value text,
    created_at timestamptz not null,
    constraint audit_events_invoice_fk
        foreign key (invoice_id)
        references invoices (id)
        on delete cascade
);

create index audit_events_owner_invoice_idx
    on audit_events (user_id, invoice_id);

create table erp_push_receipts (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    invoice_id uuid not null,
    idempotency_key varchar(128) not null,
    response_json text not null,
    created_at timestamptz not null,
    constraint erp_push_receipts_invoice_fk
        foreign key (invoice_id)
        references invoices (id)
        on delete cascade,
    constraint erp_push_receipts_owner_key_uq unique (user_id, idempotency_key)
);

create index erp_push_receipts_owner_invoice_idx
    on erp_push_receipts (user_id, invoice_id);
