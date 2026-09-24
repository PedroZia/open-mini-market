-- Etiqueta de balança (passo 1104b1, BR-14): a loja define como a balança imprime o código — o
-- prefixo, o tamanho do código interno e o campo embutido (peso ou preço), com as casas decimais.
-- Os defaults são os da MATRIZ do MVP; quem interpreta o código é o servidor, nunca o cliente.
alter table stores
    add column internal_barcode_prefix text not null default '2' check (internal_barcode_prefix <> ''),
    add column internal_code_length integer not null default 5 check (internal_code_length > 0),
    add column scale_embedded_field text not null default 'WEIGHT' check (scale_embedded_field in ('WEIGHT', 'PRICE')),
    -- Peso sai em kg com 3 casas (grama) e preço em reais com 2: a faixa cobre as duas balanças.
    add column scale_embedded_decimals integer not null default 3 check (scale_embedded_decimals between 0 and 6);

-- Código interno do produto (o PLU que a balança imprime na etiqueta): mesmo desenho do barcode,
-- inclusive a unicidade que o soft delete libera — o índice parcial espelha o ux_products_barcode.
alter table products add column internal_code text;

create unique index ux_products_internal_code on products (store_id, internal_code) where deleted_at is null and internal_code is not null;
