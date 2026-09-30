-- Ensure invoice_files can be referenced by (id, user_id)
alter table invoice_files
    add constraint invoice_files_id_owner_uq unique (id, user_id);

create table ocr_results (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    invoice_file_id uuid not null,
    provider_request_id varchar(64),
    raw_json text not null,
    created_at timestamptz not null,
    constraint ocr_results_file_owner_fk
        foreign key (invoice_file_id, user_id)
        references invoice_files (id, user_id)
        on delete cascade,
    constraint ocr_results_file_owner_uq unique (invoice_file_id, user_id)
);

create index ocr_results_owner_file_idx
    on ocr_results (user_id, invoice_file_id);

create table normalized_invoices (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    invoice_file_id uuid not null,
    invoice_type varchar(32) not null,
    invoice_code varchar(64),
    invoice_number varchar(64),
    invoice_date date,
    amount numeric(19, 2),
    tax numeric(19, 2),
    total numeric(19, 2),
    fields_json text not null,
    created_at timestamptz not null,
    constraint normalized_invoices_file_owner_fk
        foreign key (invoice_file_id, user_id)
        references invoice_files (id, user_id)
        on delete cascade,
    constraint normalized_invoices_file_owner_uq unique (invoice_file_id, user_id)
);

create index normalized_invoices_owner_file_idx
    on normalized_invoices (user_id, invoice_file_id);
