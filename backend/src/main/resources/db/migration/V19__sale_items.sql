-- Itens da venda (§5.3): cada linha guarda o snapshot do produto no momento da inclusão
-- (barcode/name/unit/unit_price) porque alterar o cadastro depois não muda a venda em andamento
-- nem a concluída (BR-01). line_number é a ordem do item na venda; o preço é sempre
-- round(unit_price * quantity, 2, HALF_UP) (BR-02), calculado no servidor.
create table sale_items (
    id uuid primary key,
    -- Única cascata do projeto (§5.3): o item não existe sem a venda e é apagado junto com ela.
    sale_id uuid not null references sales (id) on delete cascade,
    line_number int not null,
    product_id uuid not null references products (id) on delete restrict,
    barcode_snapshot text,
    name_snapshot text not null,
    unit_snapshot text not null,
    unit_price numeric(14,2) not null,
    quantity numeric(14,3) not null check (quantity > 0),
    discount_amount numeric(14,2) not null default 0,
    line_total numeric(14,2) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    -- Um item por posição na venda: a inclusão concorrente não duplica a linha (§5.3).
    constraint ux_sale_items_sale_line_number unique (sale_id, line_number)
);

-- Rastro do produto no histórico de vendas (§5.3): relatórios por produto (passo 813).
create index ix_sale_items_product on sale_items (product_id);
