-- Chaves de idempotência (§5.3, passo 607a): a resposta da primeira chamada de uma operação de
-- dinheiro/estoque fica gravada sob a chave do header Idempotency-Key; o retry com a mesma chave
-- devolve a mesma resposta sem repetir o efeito (§8). A PK é a própria chave (text, sem surrogate):
-- o INSERT duplicado é a corrida entre duas chamadas iguais e sobe como violação de unique para o
-- adaptador traduzir.
create table idempotency_keys (
    key text primary key,
    user_id uuid not null references users (id) on delete restrict,
    method text not null,
    path text not null,
    request_hash text not null,
    status_code int not null,
    response_body jsonb not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null
);

-- Limpeza das chaves vencidas (job diário do passo 1004): a varredura é por validade.
create index ix_idempotency_keys_expires_at on idempotency_keys (expires_at);
