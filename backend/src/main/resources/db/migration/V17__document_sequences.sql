-- Sequências de documentos por loja (§5.3): a venda tira o número daqui com
-- UPDATE ... RETURNING (passo 804), o que serializa a alocação sem tabela de contadores separada.
-- A PK composta (store_id, doc_type) é a própria chave da sequência; a linha nasce na primeira
-- alocação. Sem created_at de propósito: o §5.3 define só updated_at.
create table document_sequences (
    store_id uuid not null references stores (id) on delete restrict,
    doc_type text not null,
    next_value bigint not null default 1,
    updated_at timestamptz not null default now(),
    primary key (store_id, doc_type)
);
