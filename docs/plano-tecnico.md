# PDV Minimercado — Plano Técnico (Blueprint)

> Documento de arquitetura e decisões. **Não contém código.** O roadmap incremental está em [`roadmap.md`](./roadmap.md)
> e as regras de trabalho para o coding agent estão em [`../AGENTS.md`](../AGENTS.md).

- **Versão:** 1.0
- **Data:** 2026-09-23
- **Status:** proposta aprovada para início da implementação

---

## 1. Visão geral e princípios

Sistema de PDV para minimercado, começando por **um caixa, uma loja**, com arquitetura que permita crescer
para múltiplos caixas, operadores e lojas **sem reescrita**.

Princípios, em ordem de prioridade quando houver conflito:

1. **Simplicidade primeiro** — a menor quantidade de código que resolve o problema hoje.
2. **Correção transacional** — venda, pagamento, estoque e caixa são um único fato indivisível.
3. **Auditabilidade** — se aconteceu, tem que ser reconstruível (quem, quando, o quê, em qual caixa).
4. **Evolução incremental** — cada incremento deixa o sistema funcionando e testado.
5. **Fronteiras explícitas** — módulos com dependências em uma direção só.
6. **Testabilidade** — regra de negócio testável sem HTTP e sem banco.
7. **Escalabilidade sob demanda** — nada de infraestrutura distribuída antes de existir dor medida.

Anti-objetivos explícitos: microservices, event sourcing, CQRS completo, cache distribuído, fila/broker,
Kubernetes e native image **não entram** no MVP nem na primeira versão em produção.

### Convenções globais

| Item | Convenção |
| --- | --- |
| Idioma do código | Inglês (classes, tabelas, colunas, endpoints, JSON) |
| Idioma de docs/commits | Português (pt-BR) |
| Commits | Conventional Commits, ex.: `feat(vendas): conclui venda com baixa de estoque` |
| Tabelas | `snake_case`, plural, sem palavras reservadas (`users`, não `user`) |
| IDs | UUIDv7 gerado na aplicação (exceção: `audit_events`) |
| Datas | `timestamptz` em UTC; conversão para `America/Sao_Paulo` só na borda de apresentação |
| Dinheiro | `numeric(14,2)`, arredondamento `HALF_UP` em pontos definidos (§4.4) |
| Quantidade | `numeric(14,3)` (permite venda por peso, ex. banana/kg) |
| Moeda | BRL, sem multi-moeda |

---

## 2. Arquitetura geral

```text
┌──────────────┐   ┌──────────────┐   ┌────────────────────┐
│  React Web   │   │     TUI      │   │  Futuros clientes  │
│ (retaguarda) │   │    (PDV)     │   │ (impressora, app)  │
└──────┬───────┘   └──────┬───────┘   └─────────┬──────────┘
       │  HTTPS/JSON      │  HTTPS/JSON          │
       └──────────┬───────┴──────────────────────┘
                  ▼
        ┌──────────────────────────────────────┐
        │        Quarkus API (monólito         │
        │        modular, stateless)           │
        │  api → application → domain          │
        │            ↕                         │
        │      infrastructure (JPA, Flyway)    │
        └──────────────┬───────────────────────┘
                       ▼
                 ┌───────────┐
                 │PostgreSQL │
                 └───────────┘
```

**Decisão central: monólito modular.** Um único processo Quarkus, um único banco, **um único módulo Maven**
na primeira versão, mas com fronteiras de pacote explícitas por módulo de negócio e dependências
verificadas por testes ArchUnit. Isso entrega hoje a simplicidade de um monólito e, amanhã, a opção de
extrair um módulo para um serviço separado (ou para um Maven module) sem reescrever regra de negócio.

### 2.1 Layout do repositório (monorepo)

```text
open-mini-market/
├── backend/                 # Quarkus + Maven (API)
│   ├── src/main/java/...
│   ├── src/main/resources/db/migration/     # Flyway
│   ├── src/test/java/...
│   └── pom.xml
├── terminal/                # TUI (TypeScript + Ink) — pacote npm
├── web/                     # React + Vite + TypeScript — pacote npm
├── packages/api-client/     # tipos + client TS gerados do OpenAPI (compartilhado)
├── docs/                    # este plano + roadmap
├── docker-compose.yml       # postgres (+ pgAdmin opcional) para desenvolvimento
├── package.json             # npm workspaces (terminal, web, packages/*)
└── AGENTS.md
```

### 2.2 Layout do backend (package-by-feature)

Módulos de negócio no topo, camadas dentro de cada módulo:

```text
com.minimarket
├── shared/                  # OperationContext, ProblemDetail, Money, Ids, Clock, erros base
├── auth/                    # login, sessão, token, RBAC
├── users/                   # usuários, roles, permissões
├── catalog/                 # categorias, produtos, código de barras
├── inventory/               # saldos e movimentos de estoque
├── cash/                    # caixas (registros), sessões de caixa, sangria/suprimento
├── sales/                   # vendas, itens, descontos, pagamentos
├── customers/               # clientes
├── audit/                   # registro e consulta de auditoria
└── reports/                 # consultas agregadas (somente leitura)

# dentro de cada módulo:
<modulo>/
├── api/                     # resources REST, request/response DTOs, mappers de DTO
├── application/             # casos de uso (transação + autorização), portas
├── domain/                  # agregados e objetos de valor puros (quando existirem, §4.1)
└── infrastructure/          # entidades JPA, repositórios, queries SQL, adaptadores
```

Regras de dependência (verificadas por ArchUnit, não por disciplina):

1. `api` → `application` → `domain` / portas. Nunca o inverso.
2. `infrastructure` implementa portas de `application`/`domain`. Ninguém fora de `infrastructure` importa JPA.
3. Um módulo **não** acessa `infrastructure` de outro módulo. Comunicação entre módulos só via
   `application` (porta pública) ou evento de domínio.
4. `domain` não importa Quarkus, JPA, Jackson nem HTTP.
5. `api` não contém regra de negócio: valida forma, delega, mapeia resposta.
6. Nenhuma entidade JPA é serializada para JSON — sempre DTO.

> **Por que package-by-feature e não `domain/application/infrastructure/api` no topo?** Porque em um
> monólito modular a fronteira que importa é a **do módulo de negócio**, não a da camada técnica. Com
> pacotes por feature, "extrair vendas" no futuro é mover uma pasta; com pacotes por camada, é cirurgia.
> As camadas continuam existindo, um nível abaixo.

---

## 3. Módulos

| Módulo | Responsabilidade | Entidades principais | Depende de |
| --- | --- | --- | --- |
| `auth` | login, token de sessão, expiração/revogação, brute force, RBAC | `auth_sessions`, `users`, `roles` | `users` (leitura), `audit` |
| `users` | usuários, roles, permissões | `users`, `roles`, `permissions`, `user_roles`, `role_permissions` | `audit` |
| `catalog` | categorias, produtos, código de barras, preço | `categories`, `products` | `audit` |
| `inventory` | saldo e ledger de estoque | `product_stocks`, `stock_movements` | `catalog`, `audit` |
| `cash` | caixas, abertura/fechamento, sangria/suprimento | `cash_registers`, `cash_sessions`, `cash_movements` | `audit` |
| `sales` | venda, itens, desconto, pagamento, conclusão | `sales`, `sale_items`, `payments`, `document_sequences` | `catalog`, `inventory`, `cash`, `customers`, `audit` |
| `customers` | cadastro de clientes | `customers` | `audit` |
| `audit` | registro append-only e consulta | `audit_events` | — (só `shared`) |
| `reports` | consultas agregadas read-only | — (views/queries) | leitura de todos |
| `shared` | contexto de operação, erros, utilidades | — | — |

Grafo de dependências acíclico. `audit` é folha: qualquer módulo pode publicar evento de auditoria, e
`audit` não conhece ninguém.

---

## 4. Modelo de domínio

### 4.1 Onde existe objeto de domínio e onde não existe

Regra pragmática (evita tanto "JPA dominando o domínio" quanto uma camada de mapeamento inútil):

- **Agregados com invariantes ricas** → objeto de domínio em Java puro + entidade JPA separada + mapper.
  São apenas **dois**: `Sale` (com `SaleItem` e `Payment`) e `CashSession` (com `CashMovement`).
- **Dados de cadastro/consulta** (produto, categoria, cliente, usuário, role, estoque) → entidade JPA
  direta + repositório + validação por Bean Validation e **constraints no banco**. Sem objeto de domínio
  paralelo, sem mapper.
- Onde há dúvida, o critério é: *existe uma regra que, se violada, gera dinheiro errado ou estoque errado?*
  Se sim, domínio rico. Se não, entidade JPA com constraints.

### 4.2 Agregados e raízes

| Agregado | Raiz | Invariantes principais |
| --- | --- | --- |
| Venda | `Sale` | itens com preço snapshot; totais recalculados só no servidor; só conclui se pago; imutável após conclusão |
| Caixa (sessão) | `CashSession` | uma sessão aberta por caixa; saldo esperado derivado de movimentos; fechamento com valor contado |
| Estoque | `ProductStock` | saldo só muda por movimento no ledger; ledger é append-only |
| Catálogo | `Product` (entidade JPA) | preço ≥ 0; barcode único por loja entre produtos ativos |

### 4.3 Máquinas de estado

```text
Sale:        OPEN ──(pagamento total)──► COMPLETED
               │                             │
               └──(cancelar, se não pago)──► CANCELLED ◄──(estorno, permissão GERENTE)

CashSession: OPEN ──(fechar, sem vendas pendentes)──► CLOSED

Product/User/Customer: ACTIVE ──(desativar)──► DISABLED (soft delete via deleted_at)
```

### 4.4 Regras de negócio (contrato com o coding agent)

| ID | Regra |
| --- | --- |
| BR-01 | O item da venda guarda **snapshot** de preço, nome, unidade e código de barras no momento da inclusão. Alterar o preço do produto **não** altera vendas em andamento nem concluídas. |
| BR-02 | `line_total = round(unit_price * quantity, 2, HALF_UP)`; `subtotal = Σ line_total`; `total = max(subtotal − discount_amount, 0)`. |
| BR-03 | `discount_amount` é sempre calculado no servidor a partir de `discount_type` (`VALUE`/`PERCENT`) + `discount_value`. O cliente nunca envia valor final. |
| BR-04 | Desconto exige permissão (`sale.discount.apply`), **motivo obrigatório** e respeita `stores.max_discount_percent`. |
| BR-05 | Venda só conclui com `Σ payments(APPROVED) ≥ total`; troco só existe para pagamento em dinheiro (`tendered_amount − amount`). |
| BR-06 | Toda venda pertence a uma **sessão de caixa aberta** e a um **caixa (registro)**. Não existe venda "solta". |
| BR-07 | A venda concluída é imutável: correção = cancelamento/estorno com movimento compensatório de estoque e de caixa, com motivo e permissão de gerente. |
| BR-08 | Saldo de estoque nunca é escrito diretamente: toda alteração gera linha em `stock_movements` com `balance_after`. |
| BR-09 | `stores.allow_negative_stock` (default `true`): o PDV **não** para a fila por divergência de estoque; saldo negativo aparece em relatório e na auditoria. Configurável por loja. |
| BR-10 | Sangria e suprimento exigem motivo, permissão (`cash.withdrawal` / `cash.supply`) e são registrados como movimento do caixa. |
| BR-11 | Operador só opera no caixa ao qual sua sessão de autenticação está vinculada (login informa o caixa). |
| BR-12 | Nenhum cálculo de total, desconto, troco ou saldo é aceito do cliente — o servidor sempre recalcula. |
| BR-13 | Toda operação que move dinheiro ou estoque é idempotente (`Idempotency-Key`). |
| BR-14 | A interpretação do código de barras é do **servidor**: GTIN, código interno digitado ou etiqueta de balança (EAN-13 iniciado em `2`, com peso **ou** preço embutido, conforme configuração da loja). O cliente envia a string bruta; o servidor resolve produto e quantidade. |

### 4.5 Permissões (catálogo inicial)

```text
user.read        user.write       role.write
product.read     product.write    price.write
category.write
stock.read       stock.adjust     stock.receive
sale.create      sale.discount.apply   sale.cancel      sale.refund
payment.add      sale.complete
cash.read        cash.open        cash.close       cash.withdrawal  cash.supply
customer.read    customer.write
audit.read       report.read      user.session.revoke
```

Mapa inicial (tabela `role_permissions`, editável no banco sem deploy):

| Role | Permissões |
| --- | --- |
| `OPERADOR` | `product.read`, `sale.create`, `payment.add`, `sale.complete`, `cash.read`, `cash.open`, `cash.close`, `customer.read`, `customer.write`, `stock.read` |
| `GERENTE` | tudo de OPERADOR + `sale.discount.apply`, `sale.cancel`, `sale.refund`, `price.write`, `product.write`, `category.write`, `stock.adjust`, `stock.receive`, `cash.withdrawal`, `cash.supply`, `report.read`, `audit.read` |
| `ADMIN` | todas, incluindo `user.write`, `role.write`, `user.session.revoke` |

---

## 5. Banco de dados

### 5.1 Estratégia de IDs — UUIDv7 (app) vs BIGINT

| Critério | BIGINT identity | UUIDv4 | **UUIDv7 (escolhido)** |
| --- | --- | --- | --- |
| Ordenação/localidade de índice | ótima | ruim (fragmenta) | boa (time-ordered) |
| ID conhecido antes do `INSERT` | não | sim | **sim** |
| Geração distribuída (multi-loja, importação, clientes) | não | sim | **sim** |
| Exposição em URL/API | enumerável | ok | ok |
| Tamanho | 8 B | 16 B | 16 B |

**Decisão:** `uuid` (UUIDv7) como PK das entidades de negócio, **gerado na aplicação** (lib
`com.github.f4b6a3:uuid-creator`) — o ID existe antes do `INSERT`, o que simplifica eventos de domínio,
testes unitários e o ledger de estoque. PostgreSQL 18 tem `uuidv7()` nativo, que fica disponível como
função utilitária para seeds/manutenção, sem ser a fonte de verdade. **Exceção:** `audit_events.id` é
`bigint generated always as identity` (tabela append-only, ordenação natural, volume maior, nenhuma
necessidade de geração distribuída).

### 5.2 Migrations — Flyway

- Local: `backend/src/main/resources/db/migration`, arquivos `V{nnn}__{descricao}.sql`.
- Migrations aplicadas **nunca** são editadas; correção = nova migration.
- Produção: `quarkus.flyway.migrate-at-start=true`, `quarkus.hibernate-orm.database.generation=none`.
  **Nunca** `hbm2ddl.auto`.
- Testes usam o mesmo Flyway contra PostgreSQL real (Dev Services/Testcontainers) — migration quebrada
  quebra o build.
- Seeds de desenvolvimento ficam em `db/seed-dev` (perfil `%dev`), nunca em `db/migration`.

### 5.3 Modelo relacional inicial

Todas as tabelas têm `created_at timestamptz not null default now()`, `updated_at timestamptz not null
default now()` e `version bigint not null default 0` (lock otimista) **quando mutáveis**. FKs sempre
`ON DELETE RESTRICT` (nada de cascata destrutiva em dados de negócio; `sales → sale_items` é a única
exceção, com `ON DELETE CASCADE`).

```sql
stores(id uuid pk, code text unique not null, name text not null, timezone text not null default 'America/Sao_Paulo',
       allow_negative_stock boolean not null default true, max_discount_percent numeric(5,2) not null default 100,
       created_at, updated_at, version)
-- Seed: uma loja 'MATRIZ'. Nenhuma UI de multi-loja no MVP.

users(id uuid pk, username text unique not null, password_hash text not null, display_name text not null,
      status text not null check (status in ('ACTIVE','DISABLED')), failed_login_attempts int not null default 0,
      locked_until timestamptz, password_changed_at timestamptz, last_login_at timestamptz,
      created_at, updated_at, deleted_at timestamptz, version)
-- username normalizado (lower/trim) na aplicação. deleted_at = soft delete.

roles(id uuid pk, code text unique not null, name text not null, description text, system boolean not null default false,
      created_at, updated_at, version)
permissions(id uuid pk, code text unique not null, description text not null)          -- catálogo via migration
role_permissions(role_id fk, permission_id fk, pk(role_id, permission_id))
user_roles(user_id fk, role_id fk, granted_at timestamptz not null default now(), granted_by_user_id fk null,
           pk(user_id, role_id))

auth_sessions(id uuid pk, user_id fk not null, token_hash text unique not null, client text not null check (client in ('TUI','WEB')),
              store_id fk not null, cash_register_id fk null, ip inet, user_agent text,
              created_at, last_seen_at timestamptz not null, expires_at timestamptz not null,
              revoked_at timestamptz, revoked_reason text, version)
-- Índices: (user_id) where revoked_at is null; (expires_at).

cash_registers(id uuid pk, store_id fk not null, code text not null, name text not null, active boolean not null default true,
               created_at, updated_at, version, unique(store_id, code))
-- Seed: 'CAIXA-01'.

cash_sessions(id uuid pk, store_id fk not null, cash_register_id fk not null, status text not null check (status in ('OPEN','CLOSED')),
              opened_by_user_id fk not null, opened_at timestamptz not null, opening_amount numeric(14,2) not null check (opening_amount >= 0),
              closed_by_user_id fk null, closed_at timestamptz, counted_amount numeric(14,2), expected_amount numeric(14,2),
              difference_amount numeric(14,2), closing_notes text, created_at, updated_at, version)
-- CONSTRAINT CHAVE:  create unique index ux_cash_session_open on cash_sessions(cash_register_id) where status = 'OPEN';
-- (impede dois operadores no mesmo caixa no nível do banco)

cash_movements(id uuid pk, store_id fk not null, cash_session_id fk not null,
               type text not null check (type in ('OPENING','SALE','WITHDRAWAL','SUPPLY')),
               amount numeric(14,2) not null,                    -- com sinal: suprimento/venda +, sangria −
               payment_method text, reference_type text, reference_id uuid, reason text,
               created_by_user_id fk not null, created_at)
-- Índice: (cash_session_id, created_at).

categories(id uuid pk, store_id fk not null, name text not null, parent_id fk null, active boolean not null default true,
           sort_order int not null default 0, created_at, updated_at, version, unique(store_id, name))

products(id uuid pk, store_id fk not null, barcode text, sku text, name text not null, description text,
         category_id fk null, unit text not null check (unit in ('UN','KG')),
         price numeric(14,2) not null check (price >= 0), cost_price numeric(14,2) check (cost_price >= 0),
         min_quantity numeric(14,3), active boolean not null default true, created_at, updated_at, deleted_at, version)
-- create unique index ux_products_barcode on products(store_id, barcode) where deleted_at is null and barcode is not null;
-- Índices: (store_id, active), (category_id), gin trgm em name (fase de busca/relatórios).

product_stocks(id uuid pk, store_id fk not null, product_id fk not null, quantity numeric(14,3) not null default 0,
               updated_at, version, unique(store_id, product_id))

stock_movements(id uuid pk, store_id fk not null, product_id fk not null,
                type text not null check (type in ('INITIAL','PURCHASE_IN','SALE_OUT','RETURN_IN','ADJUSTMENT','LOSS')),
                quantity_delta numeric(14,3) not null,        -- com sinal
                balance_after numeric(14,3) not null,         -- saldo após o movimento (auditabilidade)
                unit_cost numeric(14,2), reference_type text, reference_id uuid, reason text,
                created_by_user_id fk not null, created_at)
-- Índices: (product_id, created_at desc), (reference_type, reference_id), (store_id, created_at desc).
-- APPEND-ONLY: sem update/delete (privilege revogado no banco).

customers(id uuid pk, store_id fk not null, name text not null, tax_id text, phone text, email text, notes text,
          active boolean not null default true, created_at, updated_at, deleted_at, version)
-- create unique index ux_customers_tax_id on customers(store_id, tax_id) where deleted_at is null and tax_id is not null;

document_sequences(store_id fk, doc_type text, next_value bigint not null default 1, updated_at, pk(store_id, doc_type))

sales(id uuid pk, store_id fk not null, number bigint not null, cash_session_id fk not null, cash_register_id fk not null,
      customer_id fk null, operator_user_id fk not null,
      status text not null check (status in ('OPEN','COMPLETED','CANCELLED')),
      subtotal numeric(14,2) not null default 0, discount_type text check (discount_type in ('VALUE','PERCENT')),
      discount_value numeric(14,2), discount_amount numeric(14,2) not null default 0, discount_reason text,
      discount_authorized_by_user_id fk null, total numeric(14,2) not null default 0,
      paid_amount numeric(14,2) not null default 0, change_amount numeric(14,2) not null default 0,
      item_count int not null default 0, notes text,
      created_at, updated_at, completed_at timestamptz, cancelled_at timestamptz,
      cancelled_by_user_id fk null, cancel_reason text, version, unique(store_id, number))
-- Índices: (cash_session_id), (store_id, created_at desc), (operator_user_id, created_at desc),
--          partial (store_id) where status = 'OPEN'.

sale_items(id uuid pk, sale_id fk not null on delete cascade, line_number int not null, product_id fk not null,
           barcode_snapshot text, name_snapshot text not null, unit_snapshot text not null,
           unit_price numeric(14,2) not null, quantity numeric(14,3) not null check (quantity > 0),
           discount_amount numeric(14,2) not null default 0, line_total numeric(14,2) not null,
           created_at, updated_at, unique(sale_id, line_number))
-- Índice: (product_id).

payments(id uuid pk, sale_id fk not null, method text not null check (method in ('CASH','PIX','DEBIT','CREDIT','VOUCHER')),
         amount numeric(14,2) not null check (amount > 0), tendered_amount numeric(14,2), change_amount numeric(14,2),
         status text not null default 'APPROVED' check (status in ('APPROVED','CANCELLED')),
         external_ref text, authorization_code text, created_by_user_id fk not null,
         created_at, cancelled_at timestamptz, cancel_reason text)
-- Índice: (sale_id).

idempotency_keys(key text pk, user_id fk not null, method text not null, path text not null, request_hash text not null,
                 status_code int not null, response_body jsonb not null, created_at, expires_at)
-- Índice: (expires_at). Limpeza: job diário.

audit_events(id bigint generated always as identity pk, occurred_at timestamptz not null default now(),
             store_id uuid, actor_user_id uuid, actor_username text, auth_session_id uuid,
             cash_session_id uuid, cash_register_id uuid,
             action text not null, entity_type text, entity_id uuid,
             source text not null check (source in ('API','TUI','WEB','SYSTEM')),
             request_id text, reason text, details jsonb not null default '{}'::jsonb, ip inet)
-- Índices: (occurred_at desc), (entity_type, entity_id, occurred_at desc), (actor_user_id, occurred_at desc),
--          (action, occurred_at desc), (cash_session_id). APPEND-ONLY.
```

### 5.4 Multi-loja desde o início, sem complexidade

- `store_id` presente desde a primeira migration em todas as tabelas operacionais; **uma** loja semeada.
- O backend resolve a loja atual por configuração (`minimarket.store.default-code=MATRIZ`) na v1.
- Unicidades e índices já são compostos com `store_id`, então não há migração dolorosa depois.
- **Não entra agora:** seletor de loja na UI, agregação cross-store, permissão por loja, replicação.

---

## 6. Segurança

### 6.1 JWT vs sessão stateful

| Critério | JWT | **Sessão stateful (escolhida)** |
| --- | --- | --- |
| Revogação imediata (demissão, troca de turno, senha trocada) | exige blacklist (que é estado no servidor de qualquer forma) | **nativa**: apagar/revogar a linha |
| "Sessões ativas" / logout remoto | difícil | **trivial** |
| Vincular sessão a caixa/loja e a auditoria | claims (que ficam desatualizadas) | **linha no banco, sempre atual** |
| Custo por request | zero I/O | 1 SELECT indexado (~0,1 ms) |
| Complexidade de implementação | média (assinatura, rotação, refresh) | **baixa** (token aleatório + hash) |

**Decisão: sessão stateful com token opaco.** Um PDV precisa desligar o acesso de alguém *agora* — JWT sem
estado não faz isso. O "custo" do SELECT é irrelevante no volume de um minimercado e já temos o banco.
JWT fica como opção futura apenas se surgirem consumidores externos stateless (ex.: integração de e-commerce).

### 6.2 Mecanismo

- **Token:** 32 bytes de `SecureRandom` → Base64URL (43 chars). Enviado em `Authorization: Bearer <token>`.
- **Armazenamento:** apenas o **SHA-256 do token** em `auth_sessions.token_hash` (vazamento do banco não
  entrega sessões). Comparação em tempo constante.
- **Senhas:** Argon2id (m=19 MiB, t=2, p=1 — OWASP), hash em formato PHC
  (`$argon2id$v=19$m=19456,t=2,p=1$...`), via `de.mkammerer:argon2-jvm`. O formato PHC permite trocar
  parâmetros/ algoritmo e re-hashear no próximo login bem-sucedido. Usuário inexistente verifica contra um
  hash "dummy" (evita enumeração por timing).
- **Ciclo de vida:** `expires_at` absoluto (12 h, configurável) + idle timeout (`last_seen_at`, 30 min para
  WEB / 8 h para TUI — TUI fica em operação contínua). `last_seen_at` atualizado no máximo 1×/minuto por
  sessão (evita write por request).
- **Logout:** `revoked_at = now()`, motivo `LOGOUT`. Logout em todos os dispositivos disponível.
- **Revogação automática:** ao trocar senha, ao desativar usuário, ao fechar o caixa (opcional/configurável)
  e por ação de ADMIN (`user.session.revoke`).
- **Múltiplas sessões:** permitidas (um operador no PDV + um gerente no web). `GET /auth/sessions` lista as
  do usuário; `DELETE /auth/sessions/{id}` revoga uma.
- **Vinculação:** o login da TUI envia `cashRegisterId`; a sessão guarda `store_id` + `cash_register_id`.
  Toda venda/movimento herda isso da sessão, nunca do corpo da requisição (BR-11).

### 6.3 Brute force e abuso

1. Contador de falhas por usuário (`users.failed_login_attempts`) → após 5 falhas, `locked_until = now + 15 min`
   (configurável); sucesso zera o contador.
2. Atraso fixo de 400 ms em falha de login (encarece varredura sem inflar o código).
3. Registro em auditoria de `LOGIN_FAILED`, `LOGIN_LOCKED`, `LOGIN_SUCCESS`, `LOGOUT`.
4. Mensagem de erro genérica (`"usuário ou senha inválidos"`) — nunca revela se o usuário existe.
5. Rate limit por IP no login: contador em memória no MVP (suficiente para 1 instância) com plano de mover
   para o proxy reverso (Caddy/Nginx `limit_req`) quando houver mais de uma instância.

### 6.4 RBAC e autorização

- Permissões são **dados** (`permissions` + `role_permissions`), roles são conjuntos nomeados delas.
- O usuário recebe um conjunto efetivo de permissões no login; o servidor revalida a cada request (não
  confia em cache de token).
- Verificação em **um único lugar**: `AuthorizationService.require(Permission.X)` dentro do caso de uso,
  mais `@RequirePermission("...")` no resource como porteiro declarativo. **Deny by default**: endpoint sem
  anotação e sem autenticação falha o teste global de segurança.
- Integração com Quarkus Security via `HttpAuthenticationMechanism` customizado (bearer → `SecurityIdentity`
  com roles + permissões), o que dá `@Authenticated`, `@RolesAllowed` e identidade injetável de graça.
- `OPERADOR` **não** acessa retaguarda: as rotas de gestão exigem permissão que ele não tem.

---

## 7. Auditoria

### 7.1 Alternativas

| Abordagem | O que dá | Por que não (ou sim) |
| --- | --- | --- |
| **Hibernate Envers** | histórico row-level automático (tabelas `_aud`) | Não sabe *quem* no sentido de negócio (usuário da sessão de PDV, caixa, motivo, origem); enche o banco de diffs irrelevantes; consulta de auditoria de negócio fica ruim |
| **Audit log próprio (escolhido)** | evento de negócio com ator, caixa, motivo, correlação, antes/depois mínimo | Exige disciplina de chamar o recorder (mitigado: é uma linha por caso de uso + teste) |
| Histórico de entidades por triggers | automático | Lógica no banco, difícil de testar e evoluir, não conhece contexto de aplicação |
| Soft delete | evita perda de cadastro | **complementar**, não substitui auditoria (não guarda antes/depois nem ator) |
| Event sourcing | verdade completa | Complexidade desproporcional; reconstruir estado e migrar eventos não se paga aqui |

**Decisão:** `audit_events` append-only + `AuditRecorder` explícito no caso de uso, **na mesma transação**
(se a operação falha, o evento não fica; se a operação comita, o evento comita). Soft delete (`deleted_at`)
para cadastros. Envers fica como opção futura **somente** se aparecer exigência regulatória de histórico
linha-a-linha (ex.: auditoria fiscal).

### 7.2 Modelo do evento

Campos já definidos em `audit_events` (§5.3). Semântica:

- `action`: verbo de negócio em `SCREAMING_SNAKE` — `LOGIN_SUCCESS`, `SALE_CREATED`, `SALE_ITEM_ADDED`,
  `SALE_DISCOUNT_APPLIED`, `PAYMENT_ADDED`, `SALE_COMPLETED`, `SALE_CANCELLED`, `CASH_SESSION_OPENED`,
  `CASH_WITHDRAWAL`, `CASH_SUPPLY`, `CASH_SESSION_CLOSED`, `PRODUCT_PRICE_CHANGED`, `STOCK_ADJUSTED`,
  `USER_CREATED`, `USER_DISABLED`, `ROLE_PERMISSIONS_CHANGED`, `ACCESS_DENIED`.
- `details` (jsonb): mínimo necessário — `before`/`after` apenas dos campos relevantes (ex.: preço antigo/novo,
  desconto, totais da venda), nunca o objeto inteiro.
- Contexto (ator, sessão, caixa, loja, request id, IP) vem de um `OperationContext` `@RequestScoped`
  preenchido pelo filtro de autenticação — o caso de uso não repassa isso à mão.

### 7.3 Garantias

- Append-only no nível do banco: o role da aplicação recebe apenas `INSERT, SELECT` em `audit_events`
  (teste de integração tenta `UPDATE`/`DELETE` e espera falha de permissão).
- Toda operação de escrita de dinheiro/estoque **obriga** auditoria: teste que falha se um caso de uso
  desse conjunto não produzir evento.
- Consulta: `GET /audit-events` com filtros (`entityType`, `entityId`, `actorUserId`, `action`, `from`, `to`,
  `cashSessionId`) paginada, exigindo `audit.read`. Consulta por entidade: `GET /audit-events?entityType=SALE&entityId=...`
  responde "histórico completo desta venda".
- Retenção: sem expurgo no MVP. Particionamento por mês só quando o volume justificar (>10 M linhas).

---

## 8. Consistência e concorrência

**Isolamento:** `READ COMMITTED` (padrão do PostgreSQL) + locks explícitos onde há disputa. `SERIALIZABLE`
não é usado: exige retry em todo lugar e o ganho não se aplica a estes fluxos.

| Cenário | Mecanismo | Resultado esperado |
| --- | --- | --- |
| Dois caixas vendendo ao mesmo tempo | Nenhum lock global; vendas independentes; locks só nas linhas de estoque compartilhadas | Ambas concluem; estoque correto |
| Duas vendas disputando a última unidade | `SELECT ... FOR UPDATE` nas linhas de `product_stocks`, **ordenadas por `product_id`** (evita deadlock) + `allow_negative_stock` | Sem lock pessimista global; se `false`, a segunda recebe `422 INSUFFICIENT_STOCK`; se `true`, saldo fica negativo e auditado |
| Dois operadores no mesmo caixa | **Unique index parcial** `where status='OPEN'` em `cash_sessions` | O segundo `open` recebe `409 CASH_REGISTER_ALREADY_OPEN` (garantido pelo banco, não por checagem em código) |
| Fechamento de caixa com venda em andamento | Lock pessimista na linha de `cash_sessions` + consulta de vendas `OPEN` da sessão | `409 SESSION_HAS_OPEN_SALES`; nunca fecha com venda aberta |
| Alteração de preço durante uma venda | Snapshot de preço no item (BR-01) | Venda em andamento mantém o preço capturado |
| Duplo clique / retry de rede em `POST /sales/{id}/complete` | `Idempotency-Key` + unique em `idempotency_keys` + status `COMPLETED` | Segunda chamada devolve a **mesma resposta** (`Idempotency-Replayed: true`), sem segunda baixa de estoque |
| Dois ajustes de estoque simultâneos no mesmo produto | `FOR UPDATE` na linha de saldo | Aplicados em série; `balance_after` consistente |
| Duas edições do mesmo produto (web) | Lock otimista (`version`) + `If-Match` | Segundo recebe `409 CONCURRENT_MODIFICATION` |
| Número sequencial da venda | `UPDATE document_sequences ... RETURNING` (lock de linha implícito) | Números únicos e sem buraco visível por loja |
| Venda aberta e operador abre o mesmo caixa em outra sessão | Venda pertence à sessão de caixa (BR-06) | Venda inacessível a outra sessão |

Regras de transação:

- **1 caso de uso = 1 transação**, demarcada em `application` (`@Transactional`). Repositório nunca abre transação.
- Sem chamadas HTTP/impressão/IO dentro de transação.
- Lock pessimista é usado em **poucos pontos conhecidos** (estoque, sessão de caixa, sequência) — nunca "por via das dúvidas".
- Idempotência obrigatória: `POST /sales`, `POST /sales/{id}/payments`, `POST /sales/{id}/complete`,
  `POST /sales/{id}/cancel`, `POST /cash-registers/{id}/close`, `.../withdrawals`, `.../supplies`.
  Replay devolve a resposta original armazenada; mesma chave com corpo diferente → `409 IDEMPOTENCY_KEY_REUSED`.

---

## 9. API REST

### 9.1 Convenções

- Base: `/api/v1` (versionamento por path — explícito, trivial de cachear/rotear; mudanças incompatíveis = `/api/v2`).
- JSON em `camelCase`; datas ISO-8601 com offset; dinheiro como número decimal (`1234.56`) e quantidade decimal.
- IDs sempre UUID string.
- `201 Created` + `Location` em criação; `200` em leitura/atualização; `204` em operações sem corpo.
- Documentação viva: OpenAPI em `/q/openapi` (dev) e artefato gerado no build → **fonte do client TypeScript**.
- Listas: `page` (0-based, default 0), `size` (default 20, máx 100), `sort=campo,asc|desc`, filtros por query.
  Resposta: `{ "items": [...], "page": 0, "size": 20, "totalItems": 137, "totalPages": 7 }`.
  Paginação por offset no MVP; cursor/keyset documentado como evolução para `sales`/`audit-events` em volumes grandes.
- Filtros de data: `from` (inclusivo) e `to` (exclusivo), ISO-8601.

### 9.2 Erros — RFC 9457 (`application/problem+json`)

```json
{
  "type": "https://minimarket.local/problems/sale-already-completed",
  "title": "Venda já concluída",
  "status": 409,
  "detail": "A venda 1042 já foi concluída em 2026-09-23T14:02:11Z.",
  "instance": "/api/v1/sales/019...",
  "code": "SALE_ALREADY_COMPLETED",
  "traceId": "b1f0c3...",
  "errors": [{ "field": "amount", "message": "deve ser maior que zero" }]
}
```

Códigos: `400` payload malformado · `401` não autenticado · `403` sem permissão · `404` inexistente ·
`409` conflito de estado/concorrência/idempotência · `422` regra de negócio violada · `429` rate limit ·
`500` inesperado (sem vazar stack trace). `code` é estável e é o que os clientes usam em lógica; `detail` é para humano.

### 9.3 Recursos

```text
# Autenticação / sessão
POST   /api/v1/auth/login                 {username, password, cashRegisterId?}  → {token, expiresAt, user, permissions}
POST   /api/v1/auth/logout
GET    /api/v1/auth/me
GET    /api/v1/auth/sessions
DELETE /api/v1/auth/sessions/{id}
POST   /api/v1/auth/password              {currentPassword, newPassword}
POST   /api/v1/auth/password/reset        {userId, newPassword}            (ADMIN)

# Usuários / papéis
GET    /api/v1/users?search=&active=&page=&size=
POST   /api/v1/users
GET    /api/v1/users/{id}
PUT    /api/v1/users/{id}                 (nome, display, roles)
POST   /api/v1/users/{id}/disable
POST   /api/v1/users/{id}/enable
GET    /api/v1/roles
PUT    /api/v1/roles/{code}/permissions   (ADMIN)

# Catálogo
GET    /api/v1/categories
POST   /api/v1/categories
PUT    /api/v1/categories/{id}
DELETE /api/v1/categories/{id}            (soft delete)
GET    /api/v1/products?search=&categoryId=&active=&page=&size=&sort=
POST   /api/v1/products
GET    /api/v1/products/{id}
GET    /api/v1/products/barcode/{barcode}          ← caminho quente do PDV
PUT    /api/v1/products/{id}              (If-Match: version)
PATCH  /api/v1/products/{id}/price        {price, reason}
POST   /api/v1/products/{id}/disable
POST   /api/v1/products/{id}/enable

# Estoque
GET    /api/v1/stock?search=&lowStock=&page=&size=
GET    /api/v1/stock/{productId}
GET    /api/v1/stock/{productId}/movements?from=&to=&page=&size=
POST   /api/v1/stock/{productId}/adjustments   {quantityDelta, reason}
POST   /api/v1/stock/{productId}/receipts      {quantity, unitCost, reason}   (entrada de mercadoria)

# Clientes
GET    /api/v1/customers?search=&page=&size=
POST   /api/v1/customers
GET    /api/v1/customers/{id}
PUT    /api/v1/customers/{id}
POST   /api/v1/customers/{id}/disable

# Caixa
GET    /api/v1/cash-registers
POST   /api/v1/cash-registers/{id}/open          {openingAmount}                (Idempotency-Key)
POST   /api/v1/cash-registers/{id}/close         {countedAmount, notes}         (Idempotency-Key)
POST   /api/v1/cash-registers/{id}/withdrawals   {amount, reason}               (Idempotency-Key)
POST   /api/v1/cash-registers/{id}/supplies      {amount, reason}               (Idempotency-Key)
GET    /api/v1/cash-registers/{id}/current-session
GET    /api/v1/cash-sessions/{id}
GET    /api/v1/cash-sessions/{id}/summary        (esperado × contado, por método)

# Vendas
POST   /api/v1/sales                             (Idempotency-Key) → venda OPEN na sessão do caixa logado
GET    /api/v1/sales/{id}
GET    /api/v1/sales?from=&to=&status=&cashSessionId=&operatorUserId=&page=&size=
POST   /api/v1/sales/{id}/items                  {barcode|productId, quantity}
PATCH  /api/v1/sales/{id}/items/{itemId}         {quantity}
DELETE /api/v1/sales/{id}/items/{itemId}
PUT    /api/v1/sales/{id}/discount               {type, value, reason}
DELETE /api/v1/sales/{id}/discount
PUT    /api/v1/sales/{id}/customer               {customerId}
DELETE /api/v1/sales/{id}/customer
POST   /api/v1/sales/{id}/payments               {method, amount, tenderedAmount?}   (Idempotency-Key)
DELETE /api/v1/sales/{id}/payments/{paymentId}
POST   /api/v1/sales/{id}/complete               (Idempotency-Key)
POST   /api/v1/sales/{id}/cancel                 {reason}                       (Idempotency-Key)

# Auditoria e relatórios
GET    /api/v1/audit-events?entityType=&entityId=&actorUserId=&action=&cashSessionId=&from=&to=&page=&size=
GET    /api/v1/reports/sales-summary?from=&to=&groupBy=day|operator|paymentMethod
GET    /api/v1/reports/cash-session/{id}
GET    /api/v1/reports/low-stock

# Sistema
GET    /api/v1/meta        → versão da API, loja, caixa da sessão, permissões, feature flags
                             (allowNegativeStock, maxDiscountPercent) — evita duplicar regra no cliente
GET    /q/health/*         → liveness/readiness/started
GET    /q/metrics          → Prometheus
```

### 9.4 Detalhes que evitam bugs

- `POST /sales` **não** recebe `cashSessionId` nem preço: deriva da sessão autenticada (BR-06, BR-11, BR-12).
- `GET /products/barcode/{barcode}` responde `404` com `code=PRODUCT_NOT_FOUND` (o PDV usa isso para oferecer
  cadastro rápido) e é o único endpoint candidato a cache no futuro. Ele também aceita **código interno** e
  **etiqueta de balança** (BR-14), devolvendo a `quantity` sugerida quando o código embute peso ou preço.
- `POST /sales/{id}/items` aceita `{ barcode }` **bruto** e resolve produto e quantidade no servidor
  (BR-14) — nenhum cliente interpreta código de barras.
- Operações de venda só aceitam vendas pertencentes à sessão de caixa do solicitante (`403` caso contrário).
- `PUT`/`PATCH` de cadastro aceitam `If-Match` (version) e respondem `409` em conflito.
- `GET /api/v1/meta` centraliza parâmetros de negócio para TUI e Web.

---

## 10. Frontend React (retaguarda)

Escopo: **administração**, não operação de caixa. Nada de tela de venda no web no MVP.

### 10.1 Stack mínima (cada dependência justificada)

| Peça | Escolha | Por quê |
| --- | --- | --- |
| Build | Vite + TypeScript | padrão, rápido, sem configuração |
| Rotas | React Router | padrão de fato |
| Estado servidor | TanStack Query | cache, retry, invalidação e estados de loading/erro prontos — evita reinventar |
| Formulários | React Hook Form + Zod | validação declarativa reutilizável e sem re-render desnecessário |
| Estilo | Tailwind CSS | velocidade e consistência sem criar design system |
| Testes | Vitest + Testing Library; Playwright (E2E) | mesmo runner do TUI |
| HTTP | client gerado do OpenAPI (`openapi-typescript` + fetch wrapper) | tipos sempre em sincronia com o backend |

**Não entram:** Redux/Zustand (estado servidor no Query, estado de UI local), bibliotecas de componentes
pesadas, GraphQL, i18n (pt-BR fixo no MVP), SSR.

### 10.2 Estrutura

```text
web/src/
├── app/            # providers (Query, Auth, Router), rotas, layout, error boundary
├── features/
│   ├── auth/       # login, sessão, guarda de rota
│   ├── products/   # {api.ts, hooks.ts, pages/, components/}
│   ├── categories/  ├── stock/  ├── customers/  ├── users/
│   ├── sales/      # lista + detalhe + cancelamento
│   ├── cash/       # sessões, resumo, sangria/suprimento
│   └── audit/      # consulta de eventos
├── shared/         # ui/ (botões, tabela, modal, toast), lib/ (formatters, permissions)
└── api/            # client gerado + tratamento de problem+json
```

Mesma filosofia do backend: **feature-first**, cada feature dona das suas telas, hooks e chamadas.

### 10.3 Padrões obrigatórios

- **Autenticação:** token em memória + `sessionStorage` (não `localStorage`); ao recarregar, `GET /auth/me`
  valida a sessão. `401` → logout e volta ao login; `403` → tela "sem permissão" (nunca esconder o erro).
- **Autorização:** hook `usePermission('product.write')` para esconder/desabilitar ações. O servidor é a
  autoridade; a UI só evita frustração.
- **Erros:** interceptor converte `problem+json` em `ApiError {code, title, detail, errors[]}`; formulários
  exibem `errors[]` por campo; toast para o resto.
- **Tabelas:** um componente `DataTable` único com paginação/ordenação/estado vazio/loading (usado por todas
  as features) — evita 10 implementações divergentes.
- **Cache:** `staleTime` curto (30 s) para listas, invalidação explícita após mutação
  (`queryClient.invalidateQueries(['products'])`).
- **Acessibilidade e teclado:** foco visível, `label` em tudo, navegação por tab — o público é operador em
  balcão, não usuário de mouse.

---

## 11. TUI (terminal do PDV)

### 11.1 OpenTUI vs Ink

| Critério | Ink 7 | OpenTUI 0.5.x |
| --- | --- | --- |
| Maturidade | estável há anos, majors espaçados, usado por Claude Code, Gemini CLI, Wrangler, Prisma | pré-1.0, releases mensais com quebras, usado em produção pelo OpenCode (bindings Core/Solid) |
| Runtime exigido | Node 22+ | **Bun 1.3+ ou Node 26.4+ com `--experimental-ffi`** (ESM apenas) |
| Renderização | JS + Yoga, throttle padrão de 30 fps (`maxFps` ajustável) | core em Zig via FFI, sem teto de fps |
| Componentes | `Box`, `Text`, `Static`; inputs/tabelas via comunidade | Input, Select, ScrollBox, Code, Diff, Markdown embutidos |
| Testes | `ink-testing-library` (maduro) | suporte incipiente |
| Windows | funciona em Node | depende de binário nativo/FFI — mais risco |
| Adequação a PDV | **alta**: formulários, listas e modais, redraw em ritmo humano | alta em performance, mas o PDV não redesenha em streaming |
| Custo de adoção | baixo | médio (Bun no parque, API em movimento) |
| Migração | — | ambos usam React + Yoga flexbox: portar telas é mecânico |

**Decisão: Ink para o MVP.** O PDV não é um caso de uso de streaming: a tela muda quando o operador digita
ou bipa um código — dezenas de eventos por minuto, não milhares por segundo. O teto de fps é irrelevante e
ajustável. Em troca, ganhamos runtime universal (Node 22, inclusive no Windows do caixa), API estável,
ecossistema de componentes e `ink-testing-library` para testar telas. OpenTUI fica como evolução **possível**
justamente porque a arquitetura escolhida (§11.2) isola a lógica da renderização — a migração, se acontecer,
troca a camada de view, não o domínio da tela.

### 11.2 Arquitetura da TUI (o que torna a migração barata)

```text
terminal/src/
├── core/          # LÓGICA PURA, sem React e sem Ink  ← 80% dos testes
│   ├── state.ts        # estado da tela como união discriminada (Login | SaleOpen | Paying | ...)
│   ├── reducer.ts      # transições puras (action → novo estado)
│   ├── scanner.ts      # buffer do leitor de código de barras (timing + terminador)
│   ├── keys.ts         # mapa de atalhos (F1..F12, ENTER, ESC, setas)
│   └── totals.ts       # formatação e exibição (o cálculo real é do servidor)
├── api/           # client gerado do OpenAPI + idempotency keys + retry
├── ui/            # componentes Ink (LoginScreen, SaleScreen, PaymentModal, ...)
└── index.tsx      # bootstrap: renderer Ink + loop de eventos
```

Regra de ouro: **nenhum cálculo de negócio na TUI** (BR-12). Ela envia intenções e exibe respostas.

### 11.3 UX de operação (teclado e leitor)

- **Leitor de código de barras = teclado.** O handler global detecta rajada de caracteres com intervalo
  < 50 ms terminada em `ENTER`/`TAB` e trata como bipe, **independentemente do foco** (exceto em modais).
  Isso elimina a dependência de o operador estar no campo certo.
- **O código é enviado bruto ao servidor** (BR-14): GTIN, código interno digitado ou etiqueta de balança
  (`2` + código + peso/preço). A TUI não interpreta o código nem calcula quantidade de etiqueta.
- **Multiplicador de quantidade:** operador digita `3*` e bipa → `quantity = 3` na mesma chamada.
- **Etiqueta de balança** (minimercado vende a granel): o formato varia por balança (Toledo, Filizola,
  Prix, Urano) e é **configurável por loja** — prefixo, tamanho do código interno, se o campo embutido é
  peso ou preço e as casas decimais. Produto pesável guarda `internal_code` e `unit = KG`.
- **Autoteste do leitor (`F11`):** mostra o último código lido, o intervalo entre caracteres e a
  interpretação aplicada. Metade dos chamados de "o leitor não funciona" é configuração do equipamento
  (sufixo, simbologia, layout de teclado), não do sistema.
- Mapa de teclas: `F1` ajuda · `F2` consulta de preço · `F3` cancelar item · `F4` cancelar venda ·
  `F5` desconto · `F6` cliente · `F7` sangria · `F8` suprimento · `F9` finalizar/pagamento ·
  `F10` fechar caixa · `F11` autoteste do leitor · `F12` trocar operador · `ENTER` confirmar ·
  `ESC` fechar modal/voltar · setas navegam itens · `+`/`-` alteram quantidade · `DEL` remove item.
- Tela de venda: input de bipe sempre visível, lista de itens (últimos N, com o último destacado), painel
  de totais grande, barra de status com caixa/operador/hora e atalhos.
- Feedback imediato: bipe OK (item adicionado, com nome e preço), bipe não encontrado (oferece cadastro
  rápido se tiver permissão), erro de rede com retry manual e sem perda de estado.
- Terminais alvo: 80×24 no mínimo, sem depender de mouse, cores com fallback monocromático, pt-BR.
- **Sem modo offline no MVP.** Se a rede cair, a TUI bloqueia novas operações e mostra o erro; nada de fila
  local (é a maior fonte de inconsistência possível em PDV e não se paga em loja com rede estável).

---

## 12. Escalabilidade

| Capacidade | MVP (agora) | Futuro (gatilho) |
| --- | --- | --- |
| Múltiplas instâncias do backend | 1 instância, sem estado em memória (regra de projeto) | escalar horizontalmente quando precisar: sessão, idempotência e auditoria já estão no banco → basta subir N réplicas |
| Múltiplos caixas | suportado pelo modelo (registro + sessão por caixa) | nenhuma mudança |
| Múltiplas lojas | `store_id` em tudo, 1 loja semeada | seletor de loja, permissão por loja, relatórios consolidados |
| Cache | **nenhum** — volume não justifica | Caffeine (TTL curto) para produto-por-barcode se medir latência; Redis só com múltiplas instâncias **e** necessidade de cache compartilhado |
| Filas/eventos | eventos CDI in-process (auditoria, efeitos internos) | outbox transacional + broker quando existir consumidor externo real (fiscal, e-commerce, BI) |
| Relatórios | queries diretas com índices | réplica de leitura ou modelo de leitura dedicado quando relatório competir com o caixa |
| Impressão de cupom | — | porta `ReceiptPrinter` + driver ESC/POS de rede (futuro próximo, não MVP) |
| Fiscal (NFC-e/SAT) | fora de escopo | módulo `fiscal` isolado, com estratégia plugável e tabela `fiscal_documents` |
| Rate limiting | em memória (1 instância) | proxy reverso (Caddy/Nginx) + Redis se multi-instância |
| Banco | 1 PostgreSQL | particionar `audit_events` e `stock_movements` por mês quando passar de ~10 M linhas |

Regra explícita: **nenhuma** infraestrutura distribuída entra sem um número medido que a justifique.

---

## 13. Observabilidade

| Item | MVP | Depois |
| --- | --- | --- |
| Logs | JSON estruturado (`quarkus-logging-json`) com `traceId`, `userId`, `sessionId`, `cashSessionId`, `saleId` no MDC | agregação (Loki/ELK) quando houver mais de um nó |
| Correlation ID | filtro aceita `X-Request-Id` ou gera; devolve no header e no corpo de erro | propagação entre serviços (hoje não há serviços) |
| Métricas | Micrometer + `/q/metrics` (Prometheus) — HTTP, pool de conexões, JVM + contadores de negócio (`sales_completed_total`, `sale_completion_seconds`, `login_failures_total`) | Prometheus + Grafana com dashboards (caixa por hora, erros, latência) |
| Health | `/q/health/live`, `/q/health/ready` (readiness com `SELECT 1`) | alertas de indisponibilidade |
| Tracing | **não entra** (1 processo, 1 banco) | OpenTelemetry quando existir mais de um componente ou integração externa |
| Auditoria | `audit_events` (§7) — já é observabilidade de negócio | relatórios e exportação |

Critério de aceite do MVP: um incidente em produção deve ser diagnosticável com **logs JSON + auditoria +
health**, sem precisar de ferramenta externa.

---

## 14. Testes

### 14.1 Pirâmide adotada

| Nível | Ferramenta | O que cobre | Volume |
| --- | --- | --- | --- |
| Unitário puro (sem Quarkus, sem banco) | JUnit 5 + AssertJ | `Sale`, `CashSession`, cálculo de total/desconto/troco, token, hash, máquina de estados da TUI, scanner | ~50% |
| Integração (com PostgreSQL real) | `@QuarkusTest` + Dev Services (Testcontainers) | repositórios, migrations Flyway, locks, constraints, idempotência | ~30% |
| API/contrato | `@QuarkusTest` + RestAssured | status codes, `problem+json`, paginação, autorização por endpoint, idempotência | ~15% |
| Concorrência | JUnit + `ExecutorService`/latch (+ Awaitility) | última unidade em estoque, dois `open` no mesmo caixa, fechamento com venda aberta, replay duplicado | pontual, obrigatório nos fluxos críticos |
| E2E | Playwright (web); TUI contra backend real em docker-compose | fluxo completo: login → venda → pagamento → conclusão | 3–5 cenários |

### 14.2 Regras

- **Sem H2, sem mock de banco.** Testes de persistência usam PostgreSQL de verdade (Dev Services sobe o
  container automaticamente; Flyway roda as mesmas migrations). SQL do PostgreSQL (índices parciais, `jsonb`,
  `FOR UPDATE`) é parte do contrato.
- Cada teste próximo da implementação: `SaleTest`, `SaleServiceTest`, `SaleResourceTest` no mesmo módulo.
- Nomes descritivos em pt-BR no `@DisplayName`, ex.: `"não conclui venda com pagamento insuficiente"`.
- **Teste de segurança global obrigatório:** varredura de todas as rotas registradas garantindo `401` sem
  token e `403` sem permissão. Novo endpoint que esquecer proteção quebra o build.
- **Teste de auditoria obrigatório:** para cada caso de uso que move dinheiro/estoque, assertar que
  `audit_events` recebeu o evento esperado com ator, entidade e motivo.
- **Teste de imutabilidade:** `UPDATE`/`DELETE` em `audit_events` e `stock_movements` deve falhar por permissão.
- Sem meta de cobertura percentual arbitrária; a meta é: toda BR-xx de §4.4 tem teste.
- `mvn verify` roda tudo; build verde é pré-requisito para qualquer commit de módulo concluído.
- CI (GitHub Actions) desde o passo 011: backend (`mvn verify`) + TUI/web (`npm test`, `tsc --noEmit`).

---

## 15. Decisões arquiteturais (resumo ADR)

| # | Problema | Alternativas | Decisão | Motivo | Impacto futuro |
| --- | --- | --- | --- | --- | --- |
| 1 | Como estruturar o sistema | monólito em camadas, monólito modular, microservices | **Monólito modular** (1 Maven module, package-by-feature, ArchUnit) | 1 loja e poucos usuários; microservices custam deploy, rede, consistência distribuída sem retorno | Extrair módulo = mover pasta para um módulo Maven/serviço; fronteiras já testadas |
| 2 | Autenticação | JWT, sessão stateful | **Sessão stateful (token opaco + hash)** | Revogação imediata, sessões auditáveis, vínculo com caixa | Se surgir cliente externo stateless, adicionar JWT como *segundo* mecanismo |
| 3 | IDs | BIGINT, UUIDv4, UUIDv7 | **UUIDv7 gerado na app** (auditoria: BIGINT identity) | ID antes do flush, sem hot-spot de índice, pronto para multi-loja/importação | IDs globais permitem merge entre lojas e geração no cliente |
| 4 | Auditoria | Envers, log próprio, triggers, event sourcing | **Log próprio append-only** + soft delete | Auditoria de negócio precisa de ator/caixa/motivo, não de diffs de linha | Envers pode ser somado só se houver exigência regulatória linha-a-linha |
| 5 | Migrations | Flyway, Liquibase | **Flyway** | SQL puro e determinístico, sem XML/YAML intermediário, ideal para constraints e índices parciais do PostgreSQL | Migrations viram documentação viva do schema |
| 6 | TUI | OpenTUI, Ink | **Ink** (com `core/` puro) | Runtime Node 22 universal (Windows incluso), API estável, `ink-testing-library`; performance não é gargalo no PDV | OpenTUI trocável depois; só a camada `ui/` muda |
| 7 | Estilo de API | REST, GraphQL, gRPC | **REST + problem+json + OpenAPI** | Clientes simples, cacheável, codegen de tipos para TS, fácil de testar com RestAssured | OpenAPI é a fonte dos clients; contrato versionado por path |
| 8 | Cache | nenhum, Caffeine, Redis | **Nenhum no MVP** | Volume baixo; cache prematuro cria bug de dado velho em preço/estoque | Caffeine com TTL curto se medir; Redis só com multi-instância |
| 9 | Eventos | chamadas diretas, CDI, outbox+broker | **CDI in-process** para auditoria/efeitos internos | Sem consumidor externo hoje; broker seria complexidade morta | Outbox transacional quando existir integração externa |
| 10 | Multi-loja | adiar, modelar desde já | **`store_id` desde a primeira migration** | Evita migração dolorosa e reescrita de unicidades | Multi-loja é configuração + UI, não reescrita |
| 11 | Dinheiro/quantidade | double, centavos inteiros, numeric | **`numeric` (14,2)/(14,3)** | Sem erro de ponto flutuante; quantidade fracionada permite venda por peso | Suporta KG sem mudança de schema |
| 12 | Estoque | saldo direto, ledger, ledger+saldo | **Saldo materializado + ledger append-only com `balance_after`** | Consulta rápida e histórico completo; divergência sempre explicável | Relatórios de perda/ruptura saem do ledger |
| 13 | Concorrência | SERIALIZABLE, otimista, pessimista | **READ COMMITTED + pessimista nos 3 pontos de disputa + otimista em cadastros + constraints** | Previsível, sem retry global, com garantia do banco onde importa | Escala para vários caixas sem mudança de modelo |
| 14 | Idempotência | nada, dedupe por estado, chave explícita | **`Idempotency-Key` em operações de dinheiro/estoque** | Retry de rede e duplo clique são a causa real de venda duplicada | Base pronta para clientes offline/fila futuros |
| 15 | Deploy | JVM, native image, containers | **JVM (fast-jar) + docker-compose** | Startup não é problema em PDV que fica ligado; native image custa build e reflexão | Native image pode ser avaliado depois para edge |
| 16 | Estrutura de código | camadas globais, feature-first | **Feature-first com camadas internas** | Fronteira de módulo é a que importa num monólito modular | Extração de módulo vira movimentação de pasta |
| 17 | Domínio rico vs entidades JPA | tudo rico, tudo anêmico | **Rico só em `Sale` e `CashSession`; JPA+constraints no resto** | Mapeamento só onde há invariante que vale dinheiro | Nada impede enriquecer outros agregados depois |

---

## 16. MVP

### MUST HAVE (o que define "MVP entregue")

1. Login/logout com sessão, expiração, múltiplas sessões e revogação.
2. RBAC com ADMIN/GERENTE/OPERADOR e permissões em banco.
3. Cadastro de produtos (com barcode, preço, unidade, categoria) e consulta por código de barras.
4. Abertura de caixa com valor inicial.
5. Venda: criar, adicionar/alterar/remover itens, desconto com motivo e permissão.
6. Pagamento (dinheiro/PIX/débito/crédito, múltiplos pagamentos, troco) e conclusão.
7. Baixa de estoque no ledger ao concluir a venda.
8. Sangria e suprimento com motivo.
9. Fechamento de caixa com esperado × contado e diferença.
10. Auditoria de todas as operações acima, com consulta por entidade e por período.
11. TUI operando o fluxo completo (login → venda → pagamento → conclusão → fechamento).
12. Testes: unitários das regras, integração com PostgreSQL real, API, autorização global, concorrência dos fluxos críticos.
13. Logs JSON + health checks + `/q/metrics`.
14. `docker-compose` de desenvolvimento e script de execução da TUI.

### SHOULD HAVE (logo depois do MVP)

- React Web de retaguarda (produtos, estoque, vendas, caixa, usuários, auditoria).
- Relatórios: vendas por dia/operador/método, estoque baixo, resumo de fechamento.
- Cancelamento/estorno de venda concluída com movimento compensatório.
- Cliente vinculado à venda (CPF na nota futura).
- Cadastro rápido de produto a partir da TUI (barcode não encontrado).
- Entrada de mercadoria (compra) com custo.
- Impressão de cupom (ESC/POS de rede) via porta `ReceiptPrinter`.
- Prometheus + Grafana com dashboards.
- PIN de operador para troca rápida de turno (sessão curta, permissões restritas).

### FUTURO (explicitamente fora)

- Fiscal (NFC-e/SAT) — desenhado em §18 e na Fase 14 do roadmap; **não** entra no MVP.
- Balança integrada por hardware (a etiqueta impressa pela balança já é suportada no MVP, BR-14).
- Modo offline na TUI, sincronização, fila local.
- Multi-loja operacional (UI, consolidação, permissão por loja).
- E-commerce/estoque compartilhado, fidelidade, promoções complexas, multi-moeda, multi-idioma.
- Microsserviços, event sourcing, CQRS, Redis, Kafka, Kubernetes, native image.
- BI/data warehouse, app mobile.

---

## 17. Como usar este plano com o coding agent

1. Leia `AGENTS.md` (regras de trabalho) e `docs/roadmap.md` (passos numerados).
2. Execute **um passo por vez**, na ordem. Não antecipe passos futuros, não crie abstração "para depois".
3. Para cada passo: implementar → rodar testes → validar critério de aceite → commit → atualizar o status
   do passo no roadmap.
4. Se um passo não couber em ~1 hora de trabalho ou gerar mais de ~300 linhas de mudança, **divida-o** e
   registre a divisão no roadmap antes de continuar.
5. Nunca deixe o build vermelho entre passos. `mvn verify` verde é a definição de "pronto para o próximo".
6. Ao concluir um módulo (conjunto de passos), pare e apresente: o que foi feito, testes executados,
   critérios de aceite atendidos e próximo passo sugerido.

---

## 18. Prontidão fiscal (NFC-e/SAT) — fase futura

Não entra no MVP. Esta seção existe para que o adiamento seja **consciente** e para que o desenho atual
não crie obstáculo quando a fase chegar (Fase 14 do roadmap, ≈ 3–4 semanas com API de provedor).

### 18.1 Veredito

Encaixar NFC-e nesta arquitetura é simples; **implementar** não é: a complexidade é tributária e
operacional, não de software. Com uma **API de provedor** (Focus NFe, Nuvem Fiscal, PlugNotas,
Tecnospeed, eNotas) o trabalho de software cai para ~3–4 semanas, incluindo homologação em uma UF.
Direto na SEFAZ (assinatura, SOAP, contingência, inutilização) multiplica o esforço por 3–5 e adiciona
risco de rejeição em produção com fila no caixa.

### 18.2 O que a arquitetura já entrega

| Já pronto | Uso fiscal |
| --- | --- |
| Venda transacional e imutável após concluída | o documento fiscal referencia a venda, sem acoplamento |
| `document_sequences (store_id, doc_type)` | série/número da NFC-e (`doc_type = NFCE:<serie>`) |
| `customers.tax_id` | CPF do consumidor (opcional na NFC-e) |
| `products.unit`, `barcode`, `cost_price` | unidade comercial, cEAN e base de custo |
| `stores` | CNPJ, IE, CRT/regime, CSC, ambiente (homologação/produção) |
| `audit_events.details` (jsonb) | chave de acesso, protocolo, rejeições e tentativas |
| Módulo isolado + porta | `FiscalGateway` trocável sem tocar em vendas |

### 18.3 As três armadilhas (decididas desde já)

1. **Concluir venda não pode depender da SEFAZ.** A venda comita; a emissão é assíncrona via **outbox na
   própria tabela** (`fiscal_documents` com `status = PENDING`), consumida por poller `@Scheduled` com
   `SELECT ... FOR UPDATE SKIP LOCKED` e retry com backoff. É a **única exceção pré-aprovada** à regra
   "sem fila/broker": não exige Kafka nem Redis e escala para N instâncias.
2. **Cancelamento da venda ≠ cancelamento da nota.** A janela legal varia por UF (em SP, 30 minutos).
   Fora dela, o estorno (passo 1304) não cancela o documento: emite devolução. O caso de uso de estorno
   deve consultar o estado fiscal antes de decidir.
3. **Numeração fiscal é separada da numeração da venda.** `sales.number` é sequencial interno;
   `fiscal_documents.series/number` é fiscal e **não pode ter buraco** — o número é alocado no momento da
   emissão e, se a emissão não ocorrer, precisa ser **inutilizado**.

### 18.4 Contingência (o único "offline" aceitável)

Quando o provedor/SEFAZ está indisponível, a loja define o comportamento em `fiscal_settings`:
`QUEUE_AND_PRINT_NONFISCAL` (a fila drena depois e o cupom sai não fiscal com aviso) ou `BLOCK_SALE`.
Isso é offline **fiscal**, não offline de venda: a venda continua transacionalmente normal; só o
documento fica pendente.

### 18.5 O que NÃO fazer agora

Sem colunas fiscais, sem certificado, sem CSC e sem motor de impostos no MVP. Tudo isso é migration
aditiva quando a fase chegar. Os únicos cuidados a manter são os já decididos: venda imutável, conclusão
transacional, auditoria completa e resolução de código de barras no servidor (BR-14).

### 18.6 Pré-requisitos para iniciar a fase

1. Definição do contador: **a loja precisa emitir NFC-e?** UF, regime (MEI/Simples/Presumido) e
   obrigatoriedade definem o escopo — e podem tornar a fase desnecessária.
2. Impressão de cupom funcionando (porta `ReceiptPrinter`, ESC/POS de rede), pois o DANFE-NFC-e
   simplificado com QR Code é impresso.
3. Certificado A1 válido e CSC de produção/homologação obtidos junto à SEFAZ.
4. NCM/CEST/CSOSN (ou CST) definidos por produto — etapa chata e a que mais atrasa projetos reais.
