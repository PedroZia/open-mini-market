-- Log de auditoria append-only (§5.3/§7). Sem FK de propósito: o log não bloqueia nem é
-- bloqueado por operação de negócio, e os identificadores guardados são históricos — a
-- linha referenciada pode mudar de estado (ou o usuário ser desativado) sem invalidar o evento.
create table audit_events (
    -- Exceção ao UUIDv7 (§5.3): o log é sequencial e só cresce; identity dá ordem de gravação.
    id bigint generated always as identity primary key,
    occurred_at timestamptz not null default now(),
    store_id uuid,
    actor_user_id uuid,
    actor_username text,
    auth_session_id uuid,
    cash_session_id uuid,
    cash_register_id uuid,
    action text not null,
    entity_type text,
    entity_id uuid,
    source text not null check (source in ('API', 'TUI', 'WEB', 'SYSTEM')),
    request_id text,
    reason text,
    details jsonb not null default '{}'::jsonb,
    ip inet
);

-- Consultas de §7.3: por período, por entidade, por ator, por ação e por sessão de caixa.
create index ix_audit_events_occurred_at on audit_events (occurred_at desc);
create index ix_audit_events_entity_occurred_at on audit_events (entity_type, entity_id, occurred_at desc);
create index ix_audit_events_actor_occurred_at on audit_events (actor_user_id, occurred_at desc);
create index ix_audit_events_action_occurred_at on audit_events (action, occurred_at desc);
create index ix_audit_events_cash_session_id on audit_events (cash_session_id);

-- Append-only no nível do banco (§7.3): a role da aplicação recebe só SELECT/INSERT — sem
-- UPDATE/DELETE. Em dev/test a aplicação conecta como o dono das tabelas (superuser), então
-- nenhum grant restringe essas conexões; a role existe para o deploy apontar o usuário da
-- aplicação para ela. Criada de forma idempotente para o teste de integração poder assumi-la
-- com SET LOCAL ROLE e provar a garantia.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'minimarket_app') then
        create role minimarket_app nologin;
    end if;
end
$$;

grant select, insert on table audit_events to minimarket_app;
