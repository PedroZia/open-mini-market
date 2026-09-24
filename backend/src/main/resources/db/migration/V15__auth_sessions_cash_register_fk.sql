-- FK de auth_sessions.cash_register_id (passo 607b): a V5 deixou a coluna sem FK porque
-- cash_registers só nasceu na V11. Antes da constraint, limpa de forma determinística os vínculos
-- órfãos gravados antes dela — dados pré-FK de dev/teste que apontam para caixa que nunca existiu;
-- nenhuma operação real grava caixa inexistente depois da validação do login (passo 607b).
update auth_sessions
   set cash_register_id = null
 where cash_register_id is not null
   and not exists (select 1 from cash_registers c where c.id = auth_sessions.cash_register_id);

-- A sessão aponta para o caixa físico; apagar o caixa com sessão vinculada é restrito, como nas
-- demais FKs operacionais. Sessão sem caixa (WEB, ou TUI antes da abertura) continua válida.
alter table auth_sessions
    add constraint fk_auth_sessions_cash_register
    foreign key (cash_register_id) references cash_registers (id) on delete restrict;
