-- Caixas físicos (§5.3): a TUI escolhe o caixa no login e a sessão de caixa aponta para ele.
-- O código é único por loja — duas lojas podem ter o mesmo código de caixa.
create table cash_registers (
    id uuid primary key,
    store_id uuid not null references stores (id) on delete restrict,
    code text not null,
    name text not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint ux_cash_registers_store_code unique (store_id, code)
);

-- Seed: caixa 'CAIXA-01' da loja 'MATRIZ' (§5.3). Id gerado pela função nativa do PostgreSQL,
-- como no seed de stores. O on conflict torna a inserção idempotente.
insert into cash_registers (id, store_id, code, name)
select uuidv7(), stores.id, 'CAIXA-01', 'Caixa 1'
from stores
where stores.code = 'MATRIZ'
on conflict (store_id, code) do nothing;
