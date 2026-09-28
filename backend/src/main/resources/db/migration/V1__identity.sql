create table users (
    id uuid primary key,
    email varchar(320) not null,
    password_hash varchar(100) not null,
    status varchar(20) not null,
    created_at timestamptz not null
);

create unique index users_email_lower_uq on users (lower(email));
