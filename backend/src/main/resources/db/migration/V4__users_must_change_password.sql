-- Reset de senha por ADMIN (passo 113): senha temporária definida pelo administrador obriga a troca
-- no próximo login (passos 205 e 214). Usuário criado normalmente nasce com a flag false.
alter table users add column must_change_password boolean not null default false;
