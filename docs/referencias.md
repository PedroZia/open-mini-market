# Referências — fontes de verdade por biblioteca

Este arquivo existe para uma única finalidade: **impedir que o agente invente API**. Antes de escrever
código que usa biblioteca, confirme a assinatura na fonte listada aqui.

## Regra

1. Vai usar API de biblioteca? **Consulte a fonte antes de escrever.** Não invente método, anotação,
   parâmetro, propriedade de configuração ou nome de classe.
2. Use `webfetch` na URL exata — é mais barato que descobrir o erro no `./mvnw verify`.
3. Se a documentação contradizer `docs/plano-tecnico.md`, **o plano vence** em arquitetura; registre o
   conflito para revisão em vez de decidir sozinho.
4. Biblioteca nova entra no projeto? Justifique no commit **e** acrescente a fonte aqui.

Versões fixadas: **Quarkus 3.33 LTS · Java 25 · PostgreSQL 18 · Ink 7 · TanStack Query v5** (demais
versões seguem a estável do momento da instalação). Links verificados em 2026-09-23.

---

## Backend — plataforma

| Fonte | Quando consultar |
| --- | --- |
| [Guia do Quarkus 3.33 (versionado)](https://quarkus.io/version/3.33/guides/) | ponto de entrada; **sempre prefira esta URL à versão sem versão** |
| [Quarkus — Hibernate ORM](https://quarkus.io/guides/hibernate-orm) | mapeamento JPA, `@Transactional`, persistência |
| [Quarkus — Flyway](https://quarkus.io/guides/flyway) | configuração de migration, `migrate-at-start`, perfis |
| [Quarkus — REST](https://quarkus.io/guides/rest) | endpoints, Jakarta REST, serialização |
| [Quarkus — testes](https://quarkus.io/guides/getting-started-testing) | `@QuarkusTest`, injeção em teste, Dev Services |
| [Hibernate ORM 7 — documentação](https://hibernate.org/orm/documentation/7.0/) | anotações de entidade, locking (`@Version`), fetch, `jsonb` |

## Backend — dados

| Fonte | Quando consultar |
| --- | --- |
| [PostgreSQL 18 — documentação](https://www.postgresql.org/docs/18/) | sintaxe SQL, índices parciais, `jsonb`, `SKIP LOCKED`, `EXPLAIN`, tipos numéricos |
| [Flyway — documentação](https://documentation.red-gate.com/flyway) | versionamento de migration, `R__` repeatable, convenções de nome |
| [uuid-creator](https://github.com/f4b6a3/uuid-creator) | geração de UUIDv7 na aplicação (API exata dos métodos) |

## Backend — testes

| Fonte | Quando consultar |
| --- | --- |
| [Testcontainers for Java](https://java.testcontainers.org/) | container de PostgreSQL, `reuse`, configuração |
| [ArchUnit — guia do usuário](https://www.archunit.org/userguide/html/000_Index.html) | regras de fronteira entre pacotes, DSL de arquitetura |
| [REST Assured](https://rest-assured.io/) | testes de API, matchers, extração de resposta |
| [AssertJ — documentação](https://assertj.github.io/doc/) | asserções fluentes, mensagens de falha, `BigDecimal` (`isEqualByComparingTo`) |

## Segurança

| Fonte | Quando consultar |
| --- | --- |
| [OWASP — Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) | parâmetros de Argon2id, política de senha, hash de token |
| [password4j](https://password4j.com/) | Argon2id em Java puro, saída PHC e leitura dos parâmetros (`getInstanceFromHash`) |

## Observabilidade (Fase 13)

| Fonte | Quando consultar |
| --- | --- |
| [Micrometer — referência](https://docs.micrometer.io/micrometer/reference/) | métricas, contadores, timers, registro Prometheus |

## TUI (Fase 11)

| Fonte | Quando consultar |
| --- | --- |
| [Ink](https://github.com/vadimdemedes/ink) | componentes, `useInput`, foco, layout em terminal |
| [ink-testing-library](https://github.com/vadimdemedes/ink-testing-library) | renderização em teste, simulação de teclas |

## Web (Fase 12)

| Fonte | Quando consultar |
| --- | --- |
| [React](https://react.dev/) | hooks, estado, efeitos, padrões de componente |
| [TanStack Query v5](https://tanstack.com/query/latest/docs/framework/react/overview) | queries, mutations, invalidação, `staleTime` |
| [React Hook Form](https://react-hook-form.com/docs) | formulários, validação, integração com Zod |
| [Zod](https://zod.dev/) | schemas de validação e inferência de tipos |
| [Tailwind CSS](https://tailwindcss.com/docs) | utilitários de estilo, responsividade, estados |
| [Vite](https://vite.dev/) | build, variáveis de ambiente, configuração |
| [openapi-typescript](https://openapi-ts.dev/) | geração dos tipos a partir do OpenAPI |
| [Vitest](https://vitest.dev/) | testes unitários, mocks, cobertura |
| [Testing Library — React](https://testing-library.com/docs/react-testing-library/intro/) | testes de componente orientados a comportamento |
| [Playwright](https://playwright.dev/docs/intro) | E2E, seletores, trace, codegen |

## Infra e CI

| Fonte | Quando consultar |
| --- | --- |
| [GitHub Actions](https://docs.github.com/en/actions) | workflow, cache, matriz de jobs |
| [Renovate](https://docs.renovatebot.com/) | atualização automatizada de dependências |
| [Spotless — plugin Maven](https://github.com/diffplug/spotless/tree/main/plugin-maven) | formatação Java com google-java-format: `spotless:apply`, `spotless:check` |
| [postgres — imagem Docker oficial](https://hub.docker.com/_/postgres) | variáveis do container, volume/PGDATA por versão, healthcheck |
| [Quarkus — Quarkus and Maven](https://quarkus.io/version/3.33/guides/maven-tooling) | empacotamento `fast-jar` (`target/quarkus-app`, `quarkus-run.jar`) e perfil de build |
| [Docker Hub — `maven` e `eclipse-temurin`](https://hub.docker.com/_/eclipse-temurin) | tags das imagens do `backend/Dockerfile` (build e runtime) |

---

## Notas

- **Mecanismo:** o `webfetch` já está habilitado na configuração do OpenCode; não há MCP de documentação
  neste projeto (decisão consciente: custo não se justifica para ~25 fontes conhecidas).
- **Alternativa nativa:** o OpenCode V2 suporta `references` (checkout de repositório git com refresh a
  cada 24 h) para bibliotecas cujos docs moram no próprio repo. Só vale a pena se a lista acima se
  mostrar insuficiente.
- **Ao adicionar dependência:** inclua a linha nesta tabela no mesmo commit que justifica a biblioteca.
