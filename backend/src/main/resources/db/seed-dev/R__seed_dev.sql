-- Seed de desenvolvimento (perfil %dev e testes de integração). Fica fora de db/migration para nunca
-- ir a produção. Flyway reexecuta este arquivo sempre que o conteúdo muda, então todo comando aqui
-- precisa ser idempotente. As próximas fases acrescentam nesta pasta os seeds das tabelas que
-- criarem (usuários, produtos, caixa, ...).

-- Loja atual do MVP (§5.3). A migration V1 já a cria; este insert é a rede de segurança para bancos
-- de desenvolvimento incompletos e o primeiro exemplo do padrão dos próximos seeds.
insert into stores (id, code, name)
values (uuidv7(), 'MATRIZ', 'Matriz')
on conflict (code) do nothing;
