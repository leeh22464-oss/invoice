create table import_batches (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    batch_number varchar(64) not null,
    status varchar(20) not null,
    total_files integer not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint import_batches_file_count_ck
        check (total_files between 1 and 100),
    constraint import_batches_owner_number_uq unique (user_id, batch_number),
    constraint import_batches_id_owner_uq unique (id, user_id)
);

create index import_batches_owner_created_idx
    on import_batches (user_id, created_at desc);

create table invoice_files (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    batch_id uuid not null,
    original_name varchar(255) not null,
    media_type varchar(64) not null,
    size bigint not null,
    sha256 varchar(64) not null,
    storage_key varchar(64) not null,
    status varchar(20) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint invoice_files_size_ck
        check (size between 0 and 20971520),
    constraint invoice_files_batch_owner_fk
        foreign key (batch_id, user_id)
        references import_batches (id, user_id)
        on delete cascade,
    constraint invoice_files_owner_storage_uq unique (user_id, storage_key)
);

create index invoice_files_owner_batch_idx
    on invoice_files (user_id, batch_id);

create index invoice_files_owner_id_idx
    on invoice_files (user_id, id);
