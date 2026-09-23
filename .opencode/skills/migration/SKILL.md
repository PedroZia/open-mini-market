---
name: Migration Flyway
description: Cria ou revisa uma migration Flyway do PDV seguindo as convenções do projeto — SQL puro, V{n}__, nunca editar aplicada, grants sem UPDATE/DELETE, índices parciais, timestamptz UTC, seed idempotente. Use quando o passo pedir migration, tabela nova, coluna nova, índice, constraint ou seed.
---

# Migration Flyway

Local: `backend/src/main/resources/db/migration/`. Seeds de dev: `db/seed-dev/`.
DDL de referência: `docs/plano-tecnico.md` §5.3 — leia antes de inventar coluna.

## Antes de escrever

1. **Número**: `V{n}__descricao_em_snake_case.sql`, `n` = maior existente + 1. Confira listando o diretório.
2. **Nunca edite** migration já aplicada — nem em dev. Correção = migration nova.
3. `quarkus.hibernate-orm.database.generation=none` é regra: o schema vem **só** do Flyway.

## Convenções obrigatórias

- **IDs**: `uuid` (UUIDv7 gerado na aplicação, lib `uuid-creator`). Exceção: `audit_events.id` é
  `bigint generated always as identity`.
- **Dinheiro**: `numeric(14,2)`. **Quantidade**: `numeric(14,3)`.
- **Datas**: `timestamptz` (UTC) — nunca `timestamp` sem fuso.
- Tabelas/colunas em `snake_case`, plural, sem palavra reservada.
- `store_id` em toda tabela operacional (multi-loja desde a primeira migration).
- FKs com `on delete restrict`; a única cascata é `sales → sale_items`.
- Unicidades e índices já compostos com `store_id`.
- **Índices parciais** quando o domínio pedir: `where status = 'OPEN'` (sessão de caixa aberta),
  `where deleted_at is null`, `where active`.
- Soft delete: `deleted_at timestamptz null` + índice único parcial quando houver unicidade a preservar.
- `created_at` / `updated_at timestamptz not null default now()` em toda tabela.
- **Nada fiscal** (NCM, CSC, certificado) antes da Fase 14 do roadmap.

## Grants para tabelas sensíveis

- `audit_events`: role da aplicação com `INSERT`/`SELECT`, **sem `UPDATE`/`DELETE`**.
- `fiscal_documents` (Fase 14): mesma regra.

## Seed de dev

`db/seed-dev/R__seed_dev.sql`, **idempotente** (`on conflict do nothing`), carregado só no perfil `%dev`.
Reexecutar não pode duplicar nada.

## Testes

- Teste de integração que **falha se a garantia não existir** — ex.: tentar `UPDATE` em `audit_events` e
  esperar erro de permissão; inserir duplicata e esperar violação de unique; tentar abrir duas sessões no
  mesmo caixa e esperar `409`.
- Migration quebrada derruba os testes de todos: rode `cd backend && ./mvnw verify` (Windows:
  `cd backend; .\mvnw.cmd verify`) antes de commitar.
