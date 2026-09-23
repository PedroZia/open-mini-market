# open-mini-market

PDV (Ponto de Venda) para minimercado: simples de operar, seguro e auditável, com arquitetura preparada
para crescer para múltiplos caixas, operadores e lojas — sem reescrever nada.

```text
React Web ──────┐
                │
TUI (PDV) ──────┼──> Quarkus API ──> PostgreSQL
                │
Futuros clientes┘
```

O backend concentra **toda** a regra de negócio. TUI e Web são clientes finos.

## Documentação

| Documento | Conteúdo |
| --- | --- |
| [`docs/plano-tecnico.md`](docs/plano-tecnico.md) | arquitetura, módulos, domínio, banco, segurança, auditoria, API, TUI, React, testes, escalabilidade, decisões (ADR) e MVP |
| [`docs/roadmap.md`](docs/roadmap.md) | roadmap granular (passos numerados com objetivo, dependências, testes, aceite e commit) |
| [`AGENTS.md`](AGENTS.md) | regras de trabalho para o coding agent (um passo por vez, DoD, convenções) |

## Stack

- **Backend:** Java 25 · Quarkus 3.33 LTS · Hibernate ORM/JPA · Flyway · PostgreSQL 18 · Maven
- **PDV (terminal):** TypeScript · Ink 7 (Node 22+)
- **Retaguarda (web):** React · TypeScript · Vite · TanStack Query
- **Compartilhado:** client TypeScript gerado do OpenAPI

## Estrutura

```text
backend/                API Quarkus (monólito modular, package-by-feature)
terminal/               TUI do PDV (Ink)
web/                    React Web (retaguarda)
packages/api-client/    client/tipos TS gerados do OpenAPI
docs/                   plano técnico e roadmap
docker-compose.yml      PostgreSQL (+ app) para desenvolvimento
```

## Estado do projeto

📋 **Planejamento concluído — implementação ainda não iniciada.**
O próximo passo é o `001` do [`docs/roadmap.md`](docs/roadmap.md) (estrutura do monorepo).

## Decisões centrais (resumo)

- **Monólito modular** em um único módulo Maven, com fronteiras de módulo verificadas por ArchUnit.
- **Sessão stateful** com token opaco (revogação imediata) em vez de JWT.
- **UUIDv7** gerado na aplicação como chave primária.
- **Auditoria própria** append-only (`audit_events`) com ator, caixa, motivo e correlação — não Envers.
- **Flyway** para schema; nunca `hbm2ddl.auto`.
- **Ink** para a TUI (Node universal e estável), com núcleo lógico puro para permitir migração futura.
- **Sem** cache, fila, broker, microsserviço ou native image antes de existir dor medida.
- **Fiscal (NFC-e) fora do MVP**, com fase dedicada já desenhada (§18 do plano + Fase 14 do roadmap,
  ≈ 3–4 semanas via API de provedor); a única exceção pré-aprovada a "sem fila" é o outbox da emissão fiscal.
