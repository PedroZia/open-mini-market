-- Cadastro rápido do PDV (F-02, passo 1122): o OPERADOR cadastra o produto desconhecido sem sair da
-- venda. A permissão é separada de product.write — o operador cria o essencial, não ganha a edição
-- do catálogo — e o mapa role→permissão continua no banco, editável sem deploy (§4.5).

insert into permissions (id, code, description) values
    (uuidv7(), 'product.quick_create', 'Cadastrar produto pelo PDV')
on conflict (code) do nothing;

-- OPERADOR (passo 1122) e, por §4.5, GERENTE (tudo de OPERADOR + 12) e ADMIN (todas): as linhas do
-- seed do V3 não alcançam a permissão nova. Junção por code, como no V3, para o seed ser idempotente
-- mesmo se as roles/permissões já existirem com outros ids.
insert into role_permissions (role_id, permission_id)
select role.id, permission.id
from (values
    ('OPERADOR', 'product.quick_create'),
    ('GERENTE', 'product.quick_create'),
    ('ADMIN', 'product.quick_create')
) as mapa(role_code, permission_code)
join roles role on role.code = mapa.role_code
join permissions permission on permission.code = mapa.permission_code
on conflict do nothing;
