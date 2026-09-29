create table ocr_credentials (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    api_key_iv bytea not null,
    api_key_ciphertext bytea not null,
    secret_key_iv bytea not null,
    secret_key_ciphertext bytea not null,
    api_key_mask varchar(32) not null,
    validation_status varchar(20) not null,
    last_validated_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ocr_credentials_user_uq unique (user_id)
);
