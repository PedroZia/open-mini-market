-- Usuários do PDV (§5.3). Usuário é global (sem store_id): a loja pertence à sessão
-- (auth_sessions.store_id) e não ao cadastro.
create table users (
    id uuid primary key,
    username text not null,
    password_hash text not null,
    display_name text not null,
    status text not null check (status in ('ACTIVE', 'DISABLED')),
    failed_login_attempts int not null default 0,
    locked_until timestamptz,
    password_changed_at timestamptz,
    last_login_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    version bigint not null default 0
);

-- Unicidade só entre usuários vivos: o soft delete (deleted_at) libera o username para reuso.
create unique index ux_users_username on users (username) where deleted_at is null;
