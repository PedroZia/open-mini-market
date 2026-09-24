-- Pagamentos da venda (§5.3, passo 901): uma venda pode ter vários pagamentos, cada um com valor
-- e forma. O troco só existe para pagamento em dinheiro (BR-05) e nenhum valor calculado no
-- cliente é aceito — o servidor recalcula tudo (BR-12). O pagamento não é editável: corrigir é
-- cancelar (APPROVED → CANCELLED com motivo e autor), por isso não há updated_at nem version.
create table payments (
    id uuid primary key,
    sale_id uuid not null references sales (id) on delete restrict,
    method text not null check (method in ('CASH', 'PIX', 'DEBIT', 'CREDIT', 'VOUCHER')),
    -- Valor do pagamento é sempre positivo (§5.3): o banco recusa zero e negativo.
    amount numeric(14,2) not null check (amount > 0),
    -- Só em dinheiro (BR-05): valor entregue e troco (tendered_amount − amount).
    tendered_amount numeric(14,2),
    change_amount numeric(14,2),
    status text not null default 'APPROVED' check (status in ('APPROVED', 'CANCELLED')),
    external_ref text,
    authorization_code text,
    created_by_user_id uuid not null references users (id) on delete restrict,
    created_at timestamptz not null default now(),
    cancelled_at timestamptz,
    cancel_reason text
);

-- Pagamentos de uma venda (§5.3): a conclusão soma os aprovados para saber se cobriu o total
-- (BR-05) e o cancelamento recalcula o valor pago.
create index ix_payments_sale on payments (sale_id);
