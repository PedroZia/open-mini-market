-- RBAC como dados (§4.5/§5.3): role é um conjunto nomeado de permissões e o mapa
-- role→permissão vive no banco, editável sem deploy. Usuário é global (sem store_id).

create table roles (
    id uuid primary key,
    code text not null unique,
    name text not null,
    description text,
    system boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0
);

-- Catálogo de permissões: dados de referência criados por migration, imutáveis em runtime.
create table permissions (
    id uuid primary key,
    code text not null unique,
    description text not null
);

create table role_permissions (
    role_id uuid not null references roles (id) on delete restrict,
    permission_id uuid not null references permissions (id) on delete restrict,
    primary key (role_id, permission_id)
);

create table user_roles (
    user_id uuid not null references users (id) on delete restrict,
    role_id uuid not null references roles (id) on delete restrict,
    granted_at timestamptz not null default now(),
    granted_by_user_id uuid references users (id) on delete restrict,
    primary key (user_id, role_id)
);

-- Seed idempotente. UUIDs gerados por uuidv7() (função nativa do PostgreSQL 18), mesmo
-- precedente da V1: seed de schema não passa pela geração de UUIDv7 da aplicação.
insert into roles (id, code, name, description, system) values
    (uuidv7(), 'OPERADOR', 'Operador', 'Operação de caixa e vendas', true),
    (uuidv7(), 'GERENTE', 'Gerente', 'Gestão da loja', true),
    (uuidv7(), 'ADMIN', 'Administrador', 'Acesso total ao sistema', true)
on conflict (code) do nothing;

insert into permissions (id, code, description) values
    (uuidv7(), 'user.read', 'Consultar usuários'),
    (uuidv7(), 'user.write', 'Criar e editar usuários'),
    (uuidv7(), 'role.write', 'Editar papéis e permissões'),
    (uuidv7(), 'product.read', 'Consultar produtos'),
    (uuidv7(), 'product.write', 'Criar e editar produtos'),
    (uuidv7(), 'price.write', 'Alterar preços'),
    (uuidv7(), 'category.write', 'Criar e editar categorias'),
    (uuidv7(), 'stock.read', 'Consultar estoque'),
    (uuidv7(), 'stock.adjust', 'Ajustar estoque'),
    (uuidv7(), 'stock.receive', 'Registrar recebimento de mercadoria'),
    (uuidv7(), 'sale.create', 'Iniciar venda'),
    (uuidv7(), 'sale.discount.apply', 'Aplicar desconto na venda'),
    (uuidv7(), 'sale.cancel', 'Cancelar venda'),
    (uuidv7(), 'sale.refund', 'Estornar venda'),
    (uuidv7(), 'payment.add', 'Registrar pagamento'),
    (uuidv7(), 'sale.complete', 'Concluir venda'),
    (uuidv7(), 'cash.read', 'Consultar caixa'),
    (uuidv7(), 'cash.open', 'Abrir caixa'),
    (uuidv7(), 'cash.close', 'Fechar caixa'),
    (uuidv7(), 'cash.withdrawal', 'Registrar sangria'),
    (uuidv7(), 'cash.supply', 'Registrar suprimento'),
    (uuidv7(), 'customer.read', 'Consultar clientes'),
    (uuidv7(), 'customer.write', 'Criar e editar clientes'),
    (uuidv7(), 'audit.read', 'Consultar auditoria'),
    (uuidv7(), 'report.read', 'Consultar relatórios'),
    (uuidv7(), 'user.session.revoke', 'Revogar sessões de usuários')
on conflict (code) do nothing;

-- Mapa inicial role→permissão de §4.5. A junção por code mantém o seed idempotente mesmo se
-- as roles/permissões já existirem com outros ids.
insert into role_permissions (role_id, permission_id)
select role.id, permission.id
from (values
    -- OPERADOR (10)
    ('OPERADOR', 'product.read'),
    ('OPERADOR', 'sale.create'),
    ('OPERADOR', 'payment.add'),
    ('OPERADOR', 'sale.complete'),
    ('OPERADOR', 'cash.read'),
    ('OPERADOR', 'cash.open'),
    ('OPERADOR', 'cash.close'),
    ('OPERADOR', 'customer.read'),
    ('OPERADOR', 'customer.write'),
    ('OPERADOR', 'stock.read'),
    -- GERENTE (22): tudo de OPERADOR + 12
    ('GERENTE', 'product.read'),
    ('GERENTE', 'sale.create'),
    ('GERENTE', 'payment.add'),
    ('GERENTE', 'sale.complete'),
    ('GERENTE', 'cash.read'),
    ('GERENTE', 'cash.open'),
    ('GERENTE', 'cash.close'),
    ('GERENTE', 'customer.read'),
    ('GERENTE', 'customer.write'),
    ('GERENTE', 'stock.read'),
    ('GERENTE', 'sale.discount.apply'),
    ('GERENTE', 'sale.cancel'),
    ('GERENTE', 'sale.refund'),
    ('GERENTE', 'price.write'),
    ('GERENTE', 'product.write'),
    ('GERENTE', 'category.write'),
    ('GERENTE', 'stock.adjust'),
    ('GERENTE', 'stock.receive'),
    ('GERENTE', 'cash.withdrawal'),
    ('GERENTE', 'cash.supply'),
    ('GERENTE', 'report.read'),
    ('GERENTE', 'audit.read')
) as mapa(role_code, permission_code)
join roles role on role.code = mapa.role_code
join permissions permission on permission.code = mapa.permission_code
on conflict do nothing;

-- ADMIN (26): todas as permissões do catálogo.
insert into role_permissions (role_id, permission_id)
select role.id, permission.id
from roles role
cross join permissions permission
where role.code = 'ADMIN'
on conflict do nothing;
