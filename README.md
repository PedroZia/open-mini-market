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
| [`docs/referencias.md`](docs/referencias.md) | fontes de verdade por biblioteca — doc oficial a consultar antes de escrever API |
| [`docs/leitores.md`](docs/leitores.md) | guia de configuração do leitor de código de barras (sufixo, prefixo, simbologias, etiqueta de balança) e do autoteste `F11` |
| [`terminal/README.md`](terminal/README.md) | instalação e execução do PDV (TUI): Node 22+, `MINIMARKET_API_URL` e atalho do Windows |

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

## Subindo o ambiente (dev)

Do zero ao PDV respondendo:

```bash
docker compose up -d postgres                                # 1. banco PostgreSQL de desenvolvimento
cd backend && ./mvnw quarkus:dev "-Dquarkus.http.port=8081"  # 2. API em http://localhost:8081
cd backend && ./mvnw verify                                  # 3. build + testes
npm install && npm start                                     # 4. PDV (TUI) apontando para a API de dev
```

O passo 4 roda a TUI descrita em [`terminal/README.md`](terminal/README.md) — instalação, como apontar
para o servidor da loja (`MINIMARKET_API_URL`) e o atalho do Windows estão lá. Sem a variável, a TUI usa
`http://localhost:8081`; se subir o `quarkus:dev` sem a flag, a API fica na 8080 e o passo 4 precisa de
`MINIMARKET_API_URL=http://localhost:8080`.

Para subir banco e API em containers, use `docker compose up -d --build` depois de copiar `.env.example`
para `.env` (`APP_PORT=8081`): a API fica em http://localhost:8081 (health em `/q/health`, porta
configurável por `APP_PORT`) e roda no perfil de produção, sem o seed de dev. Nesse caso não rode o
`quarkus:dev` junto, os dois usam a mesma porta.

No Windows (PowerShell/cmd), use `.\mvnw.cmd` no lugar de `./mvnw`. Ao subir, o Flyway aplica o schema
e o seed de desenvolvimento (`backend/src/main/resources/db/seed-dev/R__seed_dev.sql`, idempotente);
copie `.env.example` para `.env` se quiser trocar porta ou credenciais.

Na primeira subida em dev o backend cria o usuário **`admin`** com a senha **`admin123`** (variável
`ADMIN_INITIAL_PASSWORD`), com **troca obrigatória no primeiro login**; se já existe ADMIN ativo, nada é
criado. Em `%prod` não há senha padrão: defina `ADMIN_INITIAL_PASSWORD` no ambiente para semear o ADMIN
inicial — sem a variável, o seed não acontece.

Os testes de integração usam PostgreSQL real via **Dev Services**: o container do banco sobe sozinho,
não é preciso subir o `docker-compose` antes — mas o **Docker precisa estar rodando**.

## Estado do projeto

🚧 **Em implementação** — o progresso passo a passo está nos checkboxes do
[`docs/roadmap.md`](docs/roadmap.md).

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
