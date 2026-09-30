create table invoices (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    invoice_file_id uuid,
    normalized_invoice_id uuid,
    invoice_type varchar(32) not null,
    invoice_code varchar(64),
    invoice_number varchar(64),
    invoice_date date,
    amount numeric(19, 2),
    tax numeric(19, 2),
    total numeric(19, 2),
    status varchar(32) not null,
    exact_duplicate_key varchar(256),
    fields_json text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint invoices_file_owner_fk
        foreign key (invoice_file_id, user_id)
        references invoice_files (id, user_id)
        on delete set null
);

create index invoices_owner_created_idx
    on invoices (user_id, created_at desc);

create index invoices_owner_status_idx
    on invoices (user_id, status);

create unique index invoices_user_exact_duplicate_key_uq
    on invoices (user_id, exact_duplicate_key)
    where exact_duplicate_key is not null
      and status in ('PASSED', 'REVIEW_REQUIRED');

create table verification_issues (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    invoice_id uuid not null,
    code varchar(64) not null,
    field_name varchar(64),
    message varchar(512) not null,
    severity varchar(16) not null,
    created_at timestamptz not null,
    constraint verification_issues_invoice_owner_fk
        foreign key (invoice_id)
        references invoices (id)
        on delete cascade
);

create index verification_issues_owner_invoice_idx
    on verification_issues (user_id, invoice_id);

create table duplicate_matches (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    invoice_id uuid not null,
    matched_invoice_id uuid not null,
    match_type varchar(32) not null,
    score numeric(10, 4),
    matched_fields_json text not null,
    created_at timestamptz not null,
    constraint duplicate_matches_invoice_fk
        foreign key (invoice_id)
        references invoices (id)
        on delete cascade,
    constraint duplicate_matches_matched_fk
        foreign key (matched_invoice_id)
        references invoices (id)
        on delete cascade
);

create index duplicate_matches_owner_invoice_idx
    on duplicate_matches (user_id, invoice_id);
