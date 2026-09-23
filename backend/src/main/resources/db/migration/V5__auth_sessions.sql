-- Sessões autenticadas (§5.3): guarda apenas o SHA-256 do token, nunca o token em claro.
-- A loja e o caixa pertencem à sessão, não ao usuário (users é global).
create table auth_sessions (
    id uuid primary key,
    user_id uuid not null references users (id) on delete restrict,
    token_hash text not null unique,
    client text not null check (client in ('TUI', 'WEB')),
    store_id uuid not null references stores (id) on delete restrict,
    -- Sem FK por enquanto: cash_registers nasce na Fase 6; a FK entra junto com a tabela de caixa.
    cash_register_id uuid,
    ip inet,
    user_agent text,
    created_at timestamptz not null default now(),
    last_seen_at timestamptz not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    revoked_reason text,
    version bigint not null default 0
);

-- Sessão revogada permanece na tabela (histórico): o índice de sessões ativas é parcial.
create index ix_auth_sessions_user_active on auth_sessions (user_id) where revoked_at is null;
create index ix_auth_sessions_expires_at on auth_sessions (expires_at);
