# Roadmap Incremental — PDV Minimercado

> Blueprint: [`plano-tecnico.md`](./plano-tecnico.md) · Regras de trabalho: [`../AGENTS.md`](../AGENTS.md)

**Como usar:** execute **um passo por vez**, na ordem. Cada passo é pequeno o bastante para caber em uma
sessão curta de trabalho (~1 h) e termina com build verde, testes passando e um commit. Não antecipe passos
futuros. Se um passo ficar grande durante a execução, **divida-o** e registre a divisão aqui antes de seguir.

## Definition of Done padrão (vale para todo passo)

1. **Implementar** exatamente o escopo do passo — nada além.
2. **Testar** no nível adequado (unitário para regra pura, integração para persistência, API para endpoint).
3. **Validar**: `./mvnw verify` verde (ou `npm test` + `tsc --noEmit` no front/TUI) e critério de aceite do passo conferido.
4. **Commitar** com a mensagem sugerida (`feat|fix|test|chore|docs(<módulo>): descrição`).
5. **Atualizar** o checkbox do passo neste arquivo (parte do commit).
6. O sistema permanece **funcional** ao final do passo. Se algo ficou temporariamente desprotegido
   (ex.: endpoints sem autenticação nas fases 1–2), isso está explicitado no passo e é resolvido no passo
   indicado — nunca fica pendente sem registro.

**Convenções:** código em inglês; docs/commits em pt-BR; um passo = um commit (salvo indicação); testes de
regra pura não sobem Quarkus; testes de persistência usam PostgreSQL real via Dev Services.

---

## Fase 0 — Fundação

- [x] **001 — Estrutura do monorepo**
  **Objetivo:** criar a árvore `backend/`, `terminal/`, `web/`, `packages/api-client/`, `docs/`, `docker-compose.yml`, `.gitignore`, `.gitattributes`, `README.md`. **Depende:** —
  **Implementar:** pastas vazias com `.gitkeep` onde necessário; `.gitignore` cobrindo Java/Maven, Node, IDE, `.env`; `.gitattributes` com `* text=auto eol=lf` (já presente no repositório — apenas confirme; neutraliza o `core.autocrlf=true` do Git for Windows sem alterar a configuração global da máquina); README com visão em 5 linhas e links para os docs.
  **Testes/aceite:** `git status` limpo após commit; árvore confere com §2.1 do plano.
  **Commit:** `chore: cria estrutura inicial do monorepo`

- [x] **002 — Projeto Quarkus rodando**
  **Objetivo:** backend Quarkus 3.33 LTS (Java 25) que sobe e responde health. **Depende:** 001
  **Implementar:** `pom.xml` com extensões `quarkus-rest`, `quarkus-rest-jackson`, `quarkus-hibernate-orm`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-hibernate-validator`, `quarkus-smallrye-health`, `quarkus-micrometer-registry-prometheus`; `application.properties` mínimo; `mvn quarkus:dev` funcionando; formatação com Spotless + google-java-format (justificar no commit: diff determinístico e revisão mais barata).
  **Testes/aceite:** `GET /q/health` retorna `UP`; `mvn verify` verde (um teste trivial de contexto).
  **Commit:** `chore(backend): cria projeto Quarkus com health check`

- [x] **003 — PostgreSQL de desenvolvimento**
  **Objetivo:** banco local via Docker Compose. **Depende:** 001
  **Implementar:** `docker-compose.yml` com `postgres:18` (porta 5432, volume nomeado, healthcheck, variáveis por `.env.example`); datasource apontando para ele no perfil `%dev`.
  **Testes/aceite:** `docker compose up -d` sobe o banco saudável e a app conecta em dev.
  **Commit:** `chore: adiciona PostgreSQL 18 no docker-compose`

- [x] **004 — Flyway + tabela `stores`**
  **Objetivo:** migrations sob controle e a loja única semeada. **Depende:** 002, 003
  **Implementar:** `V1__stores.sql` (tabela conforme §5.3, seed `MATRIZ`); `quarkus.flyway.migrate-at-start=true`; `quarkus.hibernate-orm.database.generation=none`.
  **Testes/aceite:** teste de integração lê a loja `MATRIZ`; migration roda do zero e em banco já migrado.
  **Commit:** `feat(db): configura Flyway e cria tabela stores`

- [x] **005 — Base de testes de integração**
  **Objetivo:** padrão de teste com PostgreSQL real. **Depende:** 004
  **Implementar:** dependências de teste (`quarkus-junit5`, `rest-assured`, `assertj`); primeiro `@QuarkusTest` que consulta o banco; classe base `IntegrationTestBase` (se agregar valor, sem herança forçada); `src/test/resources/testcontainers.properties` com `testcontainers.reuse.enable=true` — **escopo do projeto**, nunca `~/.testcontainers.properties` (que afetaria todos os projetos da máquina).
  **Testes/aceite:** teste passa usando Dev Services (container automático); documentar no README como rodar.
  **Commit:** `test(backend): adiciona base de testes de integracao com PostgreSQL`

- [x] **006 — Fronteiras de módulo com ArchUnit**
  **Objetivo:** garantir §2.2 por teste. **Depende:** 002
  **Implementar:** pacotes `com.minimarket.{shared,auth,users,catalog,inventory,cash,sales,customers,audit,reports}` com `package-info.java`; testes ArchUnit: `domain` não importa JPA/Quarkus/Jackson, `api` não acessa `infrastructure` de outro módulo, sem ciclos.
  **Testes/aceite:** ArchUnit verde; teste falha se alguém violar a regra (validar com exemplo temporário).
  **Commit:** `chore(backend): define modulos e regras de dependencia com ArchUnit`

- [x] **007 — Erros padronizados (RFC 9457)**
  **Objetivo:** todo erro de API no mesmo formato. **Depende:** 002
  **Implementar:** `ProblemDetail` (type, title, status, detail, instance, code, traceId, errors[]), `ErrorCode` (enum de códigos estáveis), exception mappers para validação, 404, 405, 500 e exceções base `BusinessException`/`NotFoundException`/`ConflictException`.
  **Testes/aceite:** teste de API força 404 e 400 e valida `content-type: application/problem+json` + `code` + `traceId`.
  **Commit:** `feat(shared): padroniza erros da API com problem+json`

- [x] **008 — Correlation ID e logs estruturados**
  **Objetivo:** rastrear request ponta a ponta. **Depende:** 007
  **Implementar:** filtro que lê `X-Request-Id` ou gera UUID, coloca no MDC (`traceId`) e devolve no header; `quarkus-logging-json` em `%prod`; log de acesso com método, rota, status e duração.
  **Testes/aceite:** teste de API confirma header `X-Request-Id` ecoado; log JSON em prod contém `traceId`.
  **Commit:** `feat(shared): adiciona correlation id e logs estruturados`

- [x] **009 — `GET /api/v1/meta`**
  **Objetivo:** centralizar parâmetros de negócio para os clientes. **Depende:** 004, 007
  **Implementar:** resource + `MetaResponse` (versão da API, `storeCode`, `allowNegativeStock`, `maxDiscountPercent`, `serverTime`); configuração `minimarket.store.default-code`.
  **Testes/aceite:** teste de API valida 200 e os campos vindos da loja `MATRIZ`.
  **Commit:** `feat(shared): expoe endpoint de metadados da aplicacao`

- [x] **010 — Seed de desenvolvimento e comandos no README**
  **Objetivo:** qualquer dev sobe o ambiente em 3 comandos. **Depende:** 004
  **Implementar:** `db/seed-dev/R__seed_dev.sql` (idempotente, carregado só no perfil `%dev`); README com subir banco, rodar app, rodar testes.
  **Testes/aceite:** em banco limpo, `%dev` aplica seed sem erro e reexecutar não duplica.
  **Commit:** `chore(db): adiciona seed de desenvolvimento`

- [x] **011 — CI**
  **Objetivo:** build verde obrigatório. **Depende:** 002, 005
  **Implementar:** GitHub Actions com JDK 25, cache Maven, `mvn -B verify`; job de lint/`tsc` para Node (habilitado quando existir código TS).
  **Testes/aceite:** pipeline verde no repositório remoto em push de teste.
  **Commit:** `ci: adiciona pipeline de build e testes`

- [x] **012 — Compose completo com a aplicação**
  **Objetivo:** subir backend + banco com um comando. **Depende:** 003, 004
  **Implementar:** serviço `app` no compose (build `backend/Dockerfile` multi-stage ou `mvn quarkus:dev` documentado), `depends_on` com healthcheck.
  **Testes/aceite:** `docker compose up` sobe banco e app; `/q/health` responde de dentro do compose.
  **Commit:** `chore: adiciona aplicacao ao docker-compose`

---

## Fase 1 — Usuários

> ✅ Janela fechada no passo **307a**: a política global exige token em toda a `/api/v1/*` e os endpoints desta fase
> exigem `user.read`/`user.write`/`role.write`/`user.session.revoke`. A matriz de permissões por papel é do 307b.

- [x] **101 — Migration `users`**
  **Objetivo:** tabela de usuários conforme §5.3. **Depende:** 004
  **Implementar:** `V2__users.sql` (username único, `password_hash`, `status`, contadores de falha, `deleted_at`, `version`).
  **Testes/aceite:** migration aplica do zero; constraint de `status` rejeita valor inválido (teste de integração).
  **Commit:** `feat(users): cria tabela de usuarios`

- [x] **102 — `UserEntity` + `UserRepository`**
  **Objetivo:** persistência do usuário. **Depende:** 101
  **Implementar:** entidade JPA em `users/infrastructure`; repositório com `findByUsername`, `findById`, `insert`, `update`, `softDelete`, `search(search, active, page, size)`; normalização de `username` (lowercase/trim).
  **Testes/aceite:** teste de integração: inserir, buscar, atualizar, soft delete (não aparece na busca).
  **Commit:** `feat(users): adiciona entidade e repositorio de usuarios`

- [x] **103 — Testes de integridade de usuário**
  **Objetivo:** garantir unicidade e soft delete. **Depende:** 102
  **Implementar:** testes para username duplicado (falha), reuso de username após soft delete (permitido), `created_at`/`updated_at` preenchidos, `version` incrementando.
  **Testes/aceite:** 4 testes verdes; nenhum comportamento novo de produção.
  **Commit:** `test(users): cobre unicidade e soft delete de usuarios`

- [x] **104 — Hash de senha (Argon2id)**
  **Objetivo:** senha nunca em texto puro. **Depende:** 002
  **Implementar:** `PasswordHasher` (Argon2id m=19 MiB, t=2, p=1, saída PHC) + `verify` + `needsRehash`; configuração por `application.properties`.
  **Testes/aceite:** hash ≠ senha; `verify` true/false; dois hashes da mesma senha diferem (salt); `needsRehash` detecta parâmetros antigos; verificação contra hash dummy de usuário inexistente.
  **Commit:** `feat(users): adiciona hash de senha com Argon2id`

- [x] **105 — Migration de RBAC + catálogo de permissões**
  **Objetivo:** roles e permissões como dados. **Depende:** 101
  **Implementar:** `V3__rbac.sql` (`roles`, `permissions`, `role_permissions`, `user_roles`) + seed das 3 roles e do catálogo de permissões de §4.5, com o mapa inicial role→permissão.
  **Testes/aceite:** teste de integração conta roles e permissões esperadas; ADMIN possui todas as permissões.
  **Commit:** `feat(users): cria tabelas de RBAC e catalogo de permissoes`

- [x] **106 — Repositório de RBAC**
  **Objetivo:** consultar permissões efetivas e papéis. **Depende:** 105
  **Implementar:** `RoleRepository`/`PermissionRepository`: `rolesOf(userId)`, `effectivePermissions(userId)`, `assignRoles(userId, roleCodes)`, `permissionsOf(roleCode)`, `replacePermissions(roleCode, codes)`.
  **Testes/aceite:** teste de integração: permissões efetivas de um OPERADOR; troca de permissões de role reflete na consulta.
  **Commit:** `feat(users): adiciona repositorio de roles e permissoes`

- [x] **107 — Caso de uso `CreateUser`**
  **Objetivo:** criar usuário com regra de negócio. **Depende:** 102, 104, 106
  **Implementar:** `CreateUserUseCase` (normaliza username, valida duplicidade, exige senha com política mínima de 8 caracteres, faz hash, atribui roles, status ACTIVE) + exceções de negócio.
  **Testes/aceite:** unitário: username duplicado → `ConflictException`; senha fraca → erro de validação; senha nunca aparece no objeto retornado.
  **Commit:** `feat(users): adiciona caso de uso de criacao de usuario`

- [x] **108 — API `POST /api/v1/users`**
  **Objetivo:** criar usuário via HTTP. **Depende:** 107, 007
  **Implementar:** resource + `CreateUserRequest` (Bean Validation) + `UserResponse`; `201` + `Location`; mapeamento de erros para `problem+json`.
  **Testes/aceite:** API: 201 cria; 409 username duplicado com `code=USERNAME_ALREADY_EXISTS`; 400 senha curta com `errors[]`.
  **Commit:** `feat(users): expoe criacao de usuario na API`

- [x] **109 — API `GET /api/v1/users` (paginação e busca)**
  **Objetivo:** listar usuários. **Depende:** 108
  **Implementar:** paginação `page/size/sort` + filtros `search` e `active`, resposta `{items, page, size, totalItems, totalPages}`.
  **Testes/aceite:** teste com 25 usuários: página 0/size 10 → 10 itens, `totalItems=25`; busca por nome parcial funciona; `size=500` é limitado a 100.
  **Commit:** `feat(users): lista usuarios com paginacao e busca`

- [x] **110 — API `GET /api/v1/users/{id}`**
  **Objetivo:** detalhe do usuário. **Depende:** 108
  **Implementar:** resource + resposta com roles e status.
  **Testes/aceite:** 200 com dados corretos; 404 com `code=USER_NOT_FOUND`.
  **Commit:** `feat(users): expoe detalhe de usuario`

- [x] **111 — API `PUT /api/v1/users/{id}`**
  **Objetivo:** atualizar dados e roles. **Depende:** 108, 106
  **Implementar:** atualização de `displayName` e roles; username e senha **não** mudam por aqui.
  **Testes/aceite:** 200 altera nome e roles; 404 inexistente; roles inválidas → 400 com `code=UNKNOWN_ROLE`.
  **Commit:** `feat(users): permite atualizar usuario e papeis`

- [x] **112 — Desativar/reativar usuário**
  **Objetivo:** tirar acesso sem apagar histórico. **Depende:** 111
  **Implementar:** `POST /users/{id}/disable` e `/enable` (soft delete via `deleted_at`/`status`), com regra: não é possível desativar o último ADMIN ativo.
  **Testes/aceite:** desativado não aparece na busca padrão nem pode autenticar (teste antecipado com o serviço); 409 ao tentar desativar o último ADMIN.
  **Commit:** `feat(users): permite desativar e reativar usuario`

- [x] **113 — Reset de senha por ADMIN**
  **Objetivo:** admin devolve acesso ao usuário. **Depende:** 108
  **Implementar:** `POST /users/{id}/password-reset` (admin define nova senha temporária) + flag `mustChangePassword` no usuário.
  **Testes/aceite:** senha antiga deixa de funcionar; novo hash gerado; `mustChangePassword=true`.
  **Commit:** `feat(users): adiciona reset de senha por administrador`

- [x] **114 — API de roles e permissões**
  **Objetivo:** administrar o mapa de permissões sem deploy. **Depende:** 106
  **Implementar:** `GET /api/v1/roles` (com permissões) e `PUT /api/v1/roles/{code}/permissions` (valida códigos existentes, proíbe alterar role `system` se decidido, invalida cache de permissões se houver).
  **Testes/aceite:** 200 lista roles; PUT troca permissões e a consulta reflete; código inexistente → 400 `UNKNOWN_PERMISSION`.
  **Commit:** `feat(users): expoe administracao de papeis e permissoes`

- [x] **115 — Seed do ADMIN inicial**
  **Objetivo:** primeiro acesso em ambiente novo. **Depende:** 105, 107
  **Implementar:** criação do usuário ADMIN inicial por configuração/seed de dev, com senha definida por variável de ambiente e obrigação de troca no primeiro login.
  **Testes/aceite:** em banco limpo existe 1 ADMIN ativo com `mustChangePassword=true`.
  **Commit:** `feat(users): cria administrador inicial via seed`

---

## Fase 2 — Autenticação

- [x] **201 — Migration `auth_sessions`**
  **Objetivo:** sessões persistentes. **Depende:** 105
  **Implementar:** `V5__auth_sessions.sql` conforme §5.3 + índices (`user_id` parcial onde não revogada, `expires_at`).
  **Testes/aceite:** migration aplica; `token_hash` único rejeita duplicidade.
  **Commit:** `feat(auth): cria tabela de sessoes`

- [x] **202 — Repositório de sessões**
  **Objetivo:** persistir e consultar sessões. **Depende:** 201
  **Implementar:** `AuthSessionEntity` + repo: `insert`, `findActiveByTokenHash`, `touchLastSeen`, `revoke`, `revokeAllByUser`, `listActiveByUser`, `findById`.
  **Testes/aceite:** teste de integração para cada operação; sessão revogada não é encontrada como ativa.
  **Commit:** `feat(auth): adiciona repositorio de sessoes`

- [x] **203 — Gerador e hash de token**
  **Objetivo:** token opaco seguro. **Depende:** 002
  **Implementar:** `TokenGenerator` (32 bytes `SecureRandom` → Base64URL) + `TokenHasher` (SHA-256 hex) + comparação em tempo constante.
  **Testes/aceite:** tokens distintos a cada chamada; hash estável; comparação rejeita token alterado.
  **Commit:** `feat(auth): adiciona geracao e hash de token de sessao`

- [x] **204a — Caso de uso `Login` (núcleo)**
  **Objetivo:** autenticar sem a política de lock. **Depende:** 104, 106, 202, 203
  **Implementar:** mapear os campos de autenticação na `UserEntity` (`failed_login_attempts`, `locked_until`, `last_login_at`), porta de leitura do estado de autenticação, porta de sessões, config de expiração/idle, `LoginUseCase` (busca usuário ou hash dummy; verifica senha; recusa `status != ACTIVE`/`deleted_at`; zera contador no sucesso; rehash se necessário; cria sessão com expiração absoluta 12 h e idle 30 min WEB / 8 h TUI; atualiza `last_login_at`; resolve loja e caixa).
  **Testes/aceite:** unitários com fakes — sucesso, senha errada, usuário inexistente, usuário desativado, rehash.
  **Commit:** `feat(auth): implementa nucleo do caso de uso de login`

- [x] **204b — Lock por tentativas de login**
  **Objetivo:** bloquear após 5 falhas por 15 min. **Depende:** 204a
  **Implementar:** contador de falhas, `locked_until`, config do lock, desbloqueio ao expirar.
  **Testes/aceite:** unitários — bloqueado recusa mesmo com senha certa; desbloqueio após expirar; sucesso zera contador e lock.
  **Commit:** `feat(auth): bloqueia login apos tentativas falhas`

- [x] **205 — API `POST /api/v1/auth/login`**
  **Objetivo:** login via HTTP. **Depende:** 204b
  **Implementar:** request `{username, password, cashRegisterId?}` → `{token, expiresAt, user, roles, permissions, mustChangePassword}`; mensagem genérica em falha; `cashRegisterId` validado quando informado (ainda sem tabela de caixa → aceitar nulo e validar na Fase 6).
  **Testes/aceite:** 200 com token; 401 genérico (`code=INVALID_CREDENTIALS`); 403/423 quando bloqueado (`code=ACCOUNT_LOCKED`).
  **Commit:** `feat(auth): expoe endpoint de login`

- [x] **206 — Autenticação de requisições (bearer)**
  **Objetivo:** proteger a API por token. **Depende:** 202, 203
  **Implementar:** `HttpAuthenticationMechanism` customizado (lê `Authorization: Bearer`, busca sessão ativa, valida expiração absoluta/idle, atualiza `last_seen_at` no máximo 1×/min, monta `SecurityIdentity` com roles e atributo de permissões); `401` em `problem+json`.
  **Testes/aceite:** sem token → 401; token inválido → 401; token expirado → 401 `SESSION_EXPIRED`; token válido → identidade disponível.
  **Commit:** `feat(auth): autentica requisicoes por token de sessao`

- [x] **207 — `GET /api/v1/auth/me`**
  **Objetivo:** cliente valida a sessão ao abrir. **Depende:** 206
  **Implementar:** resposta com usuário, roles, permissões, loja, caixa, expiração.
  **Testes/aceite:** 200 com dados; 401 sem token.
  **Commit:** `feat(auth): expoe dados da sessao atual`

- [x] **208 — `POST /api/v1/auth/logout`**
  **Objetivo:** encerrar sessão. **Depende:** 206
  **Implementar:** revoga a sessão atual (`revoked_reason=LOGOUT`) e responde 204; token deixa de funcionar imediatamente.
  **Testes/aceite:** após logout, `GET /auth/me` retorna 401.
  **Commit:** `feat(auth): implementa logout`

- [x] **209 — Expiração e idle timeout**
  **Objetivo:** sessão não dura para sempre. **Depende:** 206
  **Implementar:** validação de `expires_at` e de inatividade por `last_seen_at` (limites configuráveis por cliente TUI/WEB); teste com clock controlado (injetar `Clock`).
  **Testes/aceite:** sessão inativa além do limite → 401 `SESSION_IDLE_TIMEOUT`; uso renova `last_seen_at`; expiração absoluta nunca é estendida.
  **Commit:** `feat(auth): aplica expiracao absoluta e por inatividade`

- [x] **210 — Múltiplas sessões e revogação**
  **Objetivo:** ver e derrubar sessões. **Depende:** 206
  **Implementar:** `GET /auth/sessions` (lista as do usuário, com origem, IP e último uso) e `DELETE /auth/sessions/{id}` (revoga; só as próprias, ou qualquer uma com `user.session.revoke`).
  **Testes/aceite:** sessão revogada por outro dispositivo deixa de autenticar; 404 para sessão de outro usuário sem permissão.
  **Commit:** `feat(auth): lista e revoga sessoes`

- [x] **211 — Proteção contra brute force**
  **Objetivo:** dificultar ataque de senha. **Depende:** 204
  **Implementar:** confirmação dos contadores no login + atraso fixo de 400 ms em falha + lock de 15 min + teste de que sucesso zera contador.
  **Testes/aceite:** 5 falhas → 6ª tentativa bloqueada mesmo com senha correta; após o tempo, volta a permitir.
  **Commit:** `feat(auth): protege login contra forca bruta`

- [x] **212 — Rate limit básico no login por IP**
  **Objetivo:** conter varredura de usuários. **Depende:** 205
  **Implementar:** contador em memória por IP (ex.: 20 tentativas/5 min) com resposta `429` e `Retry-After`; documentar que o limite definitivo será no proxy reverso (passo 1402).
  **Testes/aceite:** 21ª tentativa do mesmo IP → 429; IPs distintos não se afetam.
  **Commit:** `feat(auth): limita tentativas de login por IP`

- [x] **213 — Revogação automática de sessões**
  **Objetivo:** cortar acesso imediatamente. **Depende:** 210, 112, 113
  **Implementar:** revogar todas as sessões ao desativar usuário e ao resetar senha; endpoint `DELETE /users/{id}/sessions` para ADMIN.
  **Testes/aceite:** usuário desativado perde acesso na requisição seguinte; reset de senha derruba sessões antigas.
  **Commit:** `feat(auth): revoga sessoes ao desativar usuario ou trocar senha`

- [x] **214 — Troca da própria senha**
  **Objetivo:** usuário troca senha com segurança. **Depende:** 206
  **Implementar:** `POST /auth/password` exigindo senha atual, validando política, re-hasheando, revogando **as outras** sessões e limpando `mustChangePassword`.
  **Testes/aceite:** senha atual errada → 400/403; sucesso derruba as demais sessões e mantém a atual; `mustChangePassword` vira false.
  **Commit:** `feat(auth): permite troca da propria senha`

---

## Fase 3 — Auditoria (infraestrutura) e Autorização

- [x] **301 — Migration `audit_events`**
  **Objetivo:** log append-only pronto para receber eventos. **Depende:** 004
  **Implementar:** `V5__audit_events.sql` conforme §5.3 + índices; **role de aplicação sem `UPDATE`/`DELETE`** nesta tabela (grants explícitos).
  **Testes/aceite:** teste de integração: `INSERT`/`SELECT` funcionam; `UPDATE`/`DELETE` falham por permissão.
  **Commit:** `feat(audit): cria tabela de auditoria append-only`

- [x] **302 — `OperationContext`**
  **Objetivo:** contexto do ator disponível para toda a aplicação. **Depende:** 206, 301
  **Implementar:** bean `@RequestScoped` com `userId`, `username`, `authSessionId`, `storeId`, `cashRegisterId`, `requestId`, `ip`, `source` (TUI/WEB/API/SYSTEM); preenchido pelo mecanismo de autenticação/filtro.
  **Testes/aceite:** teste de API confirma que os campos chegam preenchidos no contexto durante a requisição.
  **Commit:** `feat(audit): propaga contexto da operacao na requisicao`

- [x] **303 — `AuditRecorder`**
  **Objetivo:** gravar evento de negócio na mesma transação. **Depende:** 302
  **Implementar:** `AuditRecorder.record(action, entityType, entityId, reason, details)` montando o evento a partir do `OperationContext`; sem `try/catch` que engula erro (falha na auditoria derruba a transação, por decisão).
  **Testes/aceite:** teste de integração grava evento e confere todos os campos; rollback da transação não deixa evento órfão.
  **Commit:** `feat(audit): adiciona gravador de eventos de auditoria`

- [x] **304 — Auditar autenticação**
  **Objetivo:** rastrear acessos. **Depende:** 303, 204
  **Implementar:** eventos `LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGIN_LOCKED`, `LOGOUT`, `SESSION_REVOKED`.
  **Testes/aceite:** cada evento gerado no cenário correspondente, com IP e `authSessionId`.
  **Commit:** `feat(audit): audita eventos de autenticacao`

- [x] **305 — `AuthorizationService` (deny by default)**
  **Objetivo:** um único ponto de checagem de permissão. **Depende:** 206
  **Implementar:** enum `Permission` com os códigos de §4.5; `AuthorizationService.require(permission)` e `has(permission)`; exceção `ForbiddenException` → 403 `ACCESS_DENIED`.
  **Testes/aceite:** unitário com identidade fake: permissão presente passa, ausente lança 403.
  **Commit:** `feat(auth): adiciona verificacao de permissoes`

- [x] **306 — `@RequirePermission`**
  **Objetivo:** porteiro declarativo nos endpoints. **Depende:** 305
  **Implementar:** anotação + interceptor CDI aplicado a resources; sem anotação e sem `@Authenticated` → acesso negado por padrão.
  **Testes/aceite:** endpoint anotado responde 403 para quem não tem permissão e 200 para quem tem.
  **Commit:** `feat(auth): adiciona anotacao de permissao em endpoints`

- [x] **307a — Fechar a janela: política global e permissões nos endpoints de usuários e papéis**
  **Objetivo:** fechar a janela da Fase 1 — nenhuma rota de `/api/v1` sem autenticação. **Depende:** 306
  **Implementar:** política global `authenticated` em `/api/v1/*` com exceções públicas (`/auth/login`, `/meta`, `/q/health`), no lugar das políticas por rota de `/auth/*` e das três de teste; `@RequirePermission` em `UsersResource` (`user.read`, `user.write`, `user.session.revoke`) e `RolesResource` (`user.read`, `role.write`); testes das Fases 1–2 autenticando com ADMIN real (helper `TestAdmin`) ou criando a fixture pelo caso de uso.
  **Testes/aceite:** sem token `GET /users` → 401 `problem+json` (`INVALID_CREDENTIALS` + `traceId`); `GET /meta` → 200; `POST /auth/login` acessível; com token de ADMIN a API de usuários/papéis segue funcionando.
  **Commit:** `feat(auth): protege endpoints de usuarios e papeis`

- [x] **307b — Revogação de sessão alheia e matriz de permissões**
  **Objetivo:** aplicar `user.session.revoke` de verdade e provar a matriz de permissões. **Depende:** 307a
  **Implementar:** revogar sessão de outro usuário por quem tem `user.session.revoke` (hoje responde 404) e teste de matriz.
  **Testes/aceite:** matriz de permissões: OPERADOR recebe 403 em `/users`; ADMIN 200; GERENTE 403 em `role.write`.
  **Commit:** `feat(auth): revoga sessao alheia com permissao`

- [x] **308 — Teste global de segurança de rotas**
  **Objetivo:** nenhum endpoint esquecido sem proteção. **Depende:** 307a, 307b
  **Implementar:** teste que enumera as rotas registradas (`/q/openapi` ou o `Router`) e exige `401` sem token para todas as rotas de `/api/v1`, com lista explícita de exceções (`/auth/login`, `/meta`).
  **Testes/aceite:** rota nova sem proteção quebra o build; exceções justificadas no teste.
  **Commit:** `test(auth): garante autenticacao em todas as rotas`

- [x] **309 — Auditar acesso negado**
  **Objetivo:** saber quem tentou o que sem permissão. **Depende:** 306, 303
  **Implementar:** evento `ACCESS_DENIED` (rota, permissão exigida, ator, IP) para 403 em `/api/v1`.
  **Testes/aceite:** tentativa de OPERADOR em rota de ADMIN gera exatamente 1 evento.
  **Commit:** `feat(audit): registra tentativas de acesso negado`

- [x] **310a — Auditar o ciclo de vida do usuário (criar, editar, desativar e reativar)**
  **Objetivo:** rastrear administração de acessos. **Depende:** 303, 108–114
  **Implementar:** eventos `USER_CREATED`, `USER_UPDATED`, `USER_DISABLED` e `USER_ENABLED` com `before/after` mínimo, gravados no caso de uso; reativar quem já está ativo é no-op sem evento. Subpasso do 310 (diff estimado acima de 300 linhas), que ficou dividido com o **310b**.
  **Testes/aceite:** teste por operação conferindo `action`, `entityId` e `details`.
  **Commit:** `feat(audit): audita operacoes de usuarios e papeis`

- [x] **310b — Auditar reset de senha e troca de permissões de papel**
  **Objetivo:** fechar a auditoria da administração de acessos. **Depende:** 310a
  **Implementar:** eventos `PASSWORD_RESET` (details `{username, mustChangePassword}`, nunca senha nem hash) e `ROLE_PERMISSIONS_CHANGED` (`entityType = "ROLE"`, `entityId` nulo, `before/after` das permissões), gravados no caso de uso.
  **Testes/aceite:** teste por operação conferindo `action`, `entityId` e `details`.
  **Commit:** `feat(audit): audita reset de senha e permissoes de papel`

---

## Fase 4 — Catálogo (produtos e categorias)

- [x] **401 — Migration `categories`**
  **Objetivo:** agrupar produtos. **Depende:** 004
  **Implementar:** `V7__categories.sql` (nome único por loja, `parent_id` opcional, `active`, `sort_order`).
  **Testes/aceite:** migration aplica; nome duplicado na mesma loja falha.
  **Commit:** `feat(catalog): cria tabela de categorias`

- [x] **402a — Categoria: entidade e repositório**
  **Objetivo:** persistir categorias. **Depende:** 401
  **Implementar:** `CategoryEntity` em `catalog/infrastructure`, porta `CategoryStore` em `catalog/application`, `CategoryRepository` (insert com UUIDv7, `findById`, `findAll` ordenado por `sort_order`/nome, atualização, desativar, checagem de nome duplicado por loja); violação 23505 traduzida para `ConflictException(CATEGORY_NAME_ALREADY_EXISTS)`. Passo dividido do 402 original (diff estimado acima de ~300 linhas); o restante é o 402b.
  **Testes/aceite:** testes de integração de cada método; nome duplicado na mesma loja falha.
  **Commit:** `feat(catalog): adiciona entidade e repositorio de categorias`

- [x] **402b — Categoria: casos de uso e CRUD na API**
  **Objetivo:** manter categorias. **Depende:** 402a, 306
  **Implementar:** casos de uso (listar, criar, atualizar, desativar) + `GET/POST/PUT/DELETE /api/v1/categories` (delete = desativar) com `product.read`/`category.write`; `CATEGORY_NOT_FOUND`; rotas novas na lista `API_ROUTES` do `RouteSecurityTest`.
  **Testes/aceite:** CRUD completo na API; 403 para OPERADOR escrevendo; 409 nome duplicado.
  **Commit:** `feat(catalog): expoe CRUD de categorias`

- [x] **403 — Migration `products`**
  **Objetivo:** cadastro de produtos com barcode e preço. **Depende:** 401
  **Implementar:** `V8__products.sql` conforme §5.3, incluindo índice único parcial de barcode e checks de preço.
  **Testes/aceite:** barcode duplicado ativo falha; barcode igual em produto deletado é permitido; preço negativo rejeitado.
  **Commit:** `feat(catalog): cria tabela de produtos`

- [x] **404 — Produto: repositório**
  **Objetivo:** consultas do catálogo. **Depende:** 403
  **Implementar:** `ProductRepository`: `findById`, `findByBarcode`, `search(search, categoryId, active, page, size, sort)`, `insert`, `update`, `softDelete`, `existsActiveBarcode`.
  **Testes/aceite:** teste de integração para cada método, incluindo busca por nome parcial e ordenação por preço/nome.
  **Commit:** `feat(catalog): adiciona repositorio de produtos`

- [x] **405 — Caso de uso `CreateProduct`**
  **Objetivo:** criar produto com validações. **Depende:** 404
  **Implementar:** normaliza barcode (trim, sem espaços), valida preço ≥ 0, `unit` ∈ {UN, KG}, categoria existente, barcode único entre ativos; auditoria `PRODUCT_CREATED`.
  **Testes/aceite:** unitários das validações + teste de auditoria.
  **Commit:** `feat(catalog): adiciona caso de uso de criacao de produto`

- [x] **406 — API `POST /api/v1/products`**
  **Objetivo:** cadastrar produto pela API. **Depende:** 405
  **Implementar:** request/response + validação + `201` + `Location`; permissão `product.write`.
  **Testes/aceite:** 201; 409 `BARCODE_ALREADY_EXISTS`; 400 validações; 403 OPERADOR.
  **Commit:** `feat(catalog): expoe criacao de produto`

- [x] **407 — API `GET /api/v1/products`**
  **Objetivo:** listar/buscar produtos. **Depende:** 404
  **Implementar:** paginação + filtros `search`, `categoryId`, `active`, `sort`; permissão `product.read`.
  **Testes/aceite:** busca por trecho do nome; filtro por categoria; paginação correta.
  **Commit:** `feat(catalog): lista e busca produtos`

- [x] **408 — API `GET /api/v1/products/{id}`**
  **Objetivo:** detalhe do produto. **Depende:** 404
  **Implementar:** resposta completa com categoria, unidade, preço, status.
  **Testes/aceite:** 200; 404 `PRODUCT_NOT_FOUND`.
  **Commit:** `feat(catalog): expoe detalhe de produto`

- [x] **409 — API `GET /api/v1/products/barcode/{barcode}`** ⭐ caminho quente do PDV
  **Objetivo:** bipe resolve produto. **Depende:** 404
  **Implementar:** busca por barcode normalizado; só produtos ativos; resposta enxuta (id, barcode, nome, preço, unidade); `404` com `code=PRODUCT_NOT_FOUND`. O passo 1104b estende este endpoint para código interno e etiqueta de balança (BR-14).
  **Testes/aceite:** 200 para ativo; 404 para inativo/inexistente; teste de tempo de resposta < 50 ms (smoke).
  **Commit:** `feat(catalog): consulta produto por codigo de barras`

- [x] **410 — API `PUT /api/v1/products/{id}`**
  **Objetivo:** editar cadastro com controle de concorrência. **Depende:** 408
  **Implementar:** atualização de nome, categoria, unidade, descrição, `min_quantity`; exige `If-Match` com `version`.
  **Testes/aceite:** 200 com version correta; 409 `CONCURRENT_MODIFICATION` com version antiga; 428 se `If-Match` ausente (ou 400, conforme padrão adotado).
  **Commit:** `feat(catalog): permite editar produto com lock otimista`

- [x] **411 — Alteração de preço auditada**
  **Objetivo:** preço muda com rastro. **Depende:** 410
  **Implementar:** `PATCH /products/{id}/price` com `{price, reason}`; permissão `price.write`; auditoria `PRODUCT_PRICE_CHANGED` com `before`/`after`.
  **Testes/aceite:** 200 muda preço e registra auditoria; OPERADOR recebe 403; motivo obrigatório.
  **Commit:** `feat(catalog): altera preco com motivo e auditoria`

- [x] **412 — Desativar/reativar produto**
  **Objetivo:** sair do catálogo sem perder histórico. **Depende:** 405
  **Implementar:** `POST /products/{id}/disable` e `/enable`; soft delete libera o barcode para novo produto.
  **Testes/aceite:** produto desativado não aparece na busca padrão nem no barcode; barcode pode ser reutilizado; auditoria registrada.
  **Commit:** `feat(catalog): desativa e reativa produtos`

- [x] **413 — Auditoria e autorização do catálogo**
  **Objetivo:** fechar o módulo com garantias. **Depende:** 405–412
  **Implementar:** testes consolidados de permissões (OPERADOR só lê) e de eventos de auditoria de todas as operações do módulo.
  **Testes/aceite:** suíte de catálogo verde cobrindo permissões e auditoria.
  **Commit:** `test(catalog): cobre permissoes e auditoria do catalogo`

- [x] **414 — Índice trigram para busca por nome (se necessário)**
  **Objetivo:** busca textual rápida em catálogo grande. **Depende:** 407
  **Implementar:** `V9__products_search_index.sql` com `pg_trgm` + índice GIN; usar apenas se a busca atual (`lower(name) like '%x%'`) mostrar custo alto em `EXPLAIN` com dados de teste (10 k produtos).
  **Testes/aceite:** medição em 10 000 produtos (`analyze products`) → índice **não justificado**: busca `%leite%` (1000/10 000 linhas) = `Limit → Sort → Seq Scan`, 1,7 ms, 154 shared buffers (contagem idem; termos raros/largos 1,5–1,9 ms); fim a fim busca + contagem 5–7 ms no orçamento de 50 ms. GIN hipotético em `lower(name)`: 0,46 ms — economia não paga o custo de escrita/manutenção. Sem migration; se um dia for criada, será `V9__products_search_index.sql` indexando `lower(name)` (a query do 407 é `lower(p.name) like`, que não usa índice em `name` puro).
  **Commit:** `perf(catalog): mede busca por nome e dispensa indice trigram`

---

## Fase 5 — Clientes

- [x] **501 — Migration `customers`**
  **Objetivo:** cadastro de clientes. **Depende:** 004
  **Implementar:** `V10__customers.sql` (§5.3) com índice único parcial de `tax_id`.
  **Testes/aceite:** migration aplica; CPF duplicado ativo falha.
  **Commit:** `feat(customers): cria tabela de clientes`

- [x] **502a — Cliente: entidade, repositório e validação de CPF**
  **Objetivo:** persistir clientes. **Depende:** 501
  **Implementar:** `CustomerEntity` em `customers/infrastructure`, porta `CustomerStore` em `customers/application`, `CustomerRepository` (insert com UUIDv7 e loja resolvida pelo caso de uso, `findById`, `search` por nome/CPF/telefone com paginação, `update`, `disable` com soft delete, checagem de tax_id duplicado entre vivos); validação de CPF (normalização para dígitos + 2 dígitos verificadores) em `customers/application`; violação 23505 do `ux_customers_tax_id` traduzida para `ConflictException(TAX_ID_ALREADY_EXISTS)`. Passo dividido do 502 original (diff estimado acima de ~300 linhas); o restante é o 502b.
  **Testes/aceite:** integração do repositório (busca por nome parcial, CPF e telefone; CPF duplicado ativo falha; soft delete libera o tax_id) + unitário do validador de CPF.
  **Commit:** `feat(customers): adiciona entidade e repositorio de clientes`

- [x] **502b — Cliente: casos de uso e CRUD na API**
  **Objetivo:** manter clientes pela API. **Depende:** 502a
  **Implementar:** casos de uso (listar/buscar, criar, detalhar, atualizar, desativar) + `GET/POST /api/v1/customers`, `GET/PUT /api/v1/customers/{id}` e `POST /api/v1/customers/{id}/disable` com `customer.read`/`customer.write`; `CUSTOMER_NOT_FOUND`; auditoria `CUSTOMER_CREATED`/`CUSTOMER_UPDATED`/`CUSTOMER_DISABLED`; rotas novas na lista `API_ROUTES` do `RouteSecurityTest`.
  **Testes/aceite:** CRUD na API; busca por nome parcial e por CPF; CPF inválido rejeitado; CPF duplicado → 409; auditoria registrada; OPERADOR escreve (a matriz de clientes dá `customer.write` ao operador).
  **Commit:** `feat(customers): adiciona cadastro e busca de clientes`

---

## Fase 6 — Caixa

- [x] **601 — Migration `cash_registers` + seed**
  **Objetivo:** existir o caixa físico. **Depende:** 004
  **Implementar:** `V11__cash_registers.sql` + seed `CAIXA-01` na loja `MATRIZ`.
  **Testes/aceite:** existe 1 caixa ativo com código `CAIXA-01`.
  **Commit:** `feat(cash): cria tabela de caixas e seed do caixa 1`

- [x] **602 — API `GET /api/v1/cash-registers`**
  **Objetivo:** TUI escolhe o caixa no login. **Depende:** 601
  **Implementar:** lista de caixas ativos com status atual (aberto/fechado) e operador; permissão `cash.read`.
  **Testes/aceite:** 200 com status correto após abrir/fechar (teste preparado para fase seguinte).
  **Commit:** `feat(cash): lista caixas disponiveis`

- [x] **603 — Migration `cash_sessions` + `cash_movements`**
  **Objetivo:** sessão de caixa e movimentos de dinheiro. **Depende:** 601
  **Implementar:** `V12__cash_sessions.sql` + `V13__cash_movements.sql` conforme §5.3, **incluindo o índice único parcial de sessão aberta por caixa**.
  **Testes/aceite:** dois `INSERT` de sessão aberta no mesmo caixa → o segundo falha por violação de índice único.
  **Commit:** `feat(cash): cria sessoes e movimentos de caixa`

- [x] **604 — Entidades e repositórios de caixa**
  **Objetivo:** persistir sessão e movimentos. **Depende:** 603
  **Implementar:** `CashSessionEntity`, `CashMovementEntity` + repos (`findOpenByRegister`, `findById`, `insert`, `insertMovement`, `sumByType`, `lockById`).
  **Testes/aceite:** teste de integração das operações; `lockById` bloqueia concorrente (teste com duas threads).
  **Commit:** `feat(cash): adiciona repositorios de sessao e movimentos`

- [x] **605 — Domínio `CashSession`**
  **Objetivo:** regras de caixa em código puro. **Depende:** 604
  **Implementar:** objeto de domínio com: `expectedAmount` (abertura + suprimentos + entradas − sangrias − saídas), validações (valor > 0, motivo obrigatório em sangria/suprimento, não fechar sessão já fechada) + testes unitários puros.
  **Testes/aceite:** 6+ testes unitários de cálculo e invariantes, sem Quarkus.
  **Commit:** `feat(cash): adiciona modelo de dominio da sessao de caixa`

- [x] **606 — Caso de uso `OpenCashSession`**
  **Objetivo:** abrir caixa. **Depende:** 605, 302
  **Implementar:** valida caixa ativo, ausência de sessão aberta, `openingAmount` ≥ 0; cria sessão + movimento `OPENING`; auditoria `CASH_SESSION_OPENED`; permissão `cash.open`.
  **Testes/aceite:** unitário/integração: abre com sucesso; segunda abertura → `409 CASH_REGISTER_ALREADY_OPEN`; auditoria registrada.
  **Commit:** `feat(cash): implementa abertura de caixa`

- [x] **607a — Migration `idempotency_keys` + `IdempotencyService`**
  **Objetivo:** ligar o mecanismo de idempotência compartilhado antes do primeiro endpoint idempotente (dinheiro/estoque). **Depende:** 603
  **Implementar:** `V14__idempotency_keys.sql` (§5.3, com índice em `expires_at`); porta `IdempotencyKeyStore` + records `StoredIdempotentResponse`/`NewIdempotencyRecord` + `IdempotencyService` em `shared/application` (replay só com `userId + method + path + hash` iguais, senão `409 IDEMPOTENCY_KEY_REUSED`; `expires_at` = `Clock` + `minimarket.idempotency.ttl`, 24 h); entidade e repositório em `shared/infrastructure` (PK é a própria `key`; 23505 → `ConflictException(IDEMPOTENCY_KEY_REUSED)`); `IdempotencyGuard` em `shared/api` (header `Idempotency-Key` obrigatório → 400, hash SHA-256 do corpo, replay com status/corpo gravados + `Idempotency-Replayed: true`, grava só resposta < 400); códigos novos `IDEMPOTENCY_KEY_REQUIRED` (400) e `IDEMPOTENCY_KEY_REUSED` (409). Subpasso criado antes do 607 para o mecanismo compartilhado nascer pronto e testado, sem endpoint novo: o primeiro consumidor é o próprio 607.
  **Testes/aceite:** migration aplica do zero (colunas + PK + índice); repositório: round-trip (inclusive corpo `null` em jsonb) e chave repetida → `ConflictException`; guard unitário sem Quarkus: sem header → 400, primeira chamada grava e devolve a ação, replay não roda a ação, hash/usuário divergente → 409, corrida perdida no record → replay do vencedor.
  **Commit:** `feat(shared): adiciona idempotencia de requisicoes`

- [x] **607 — API `POST /api/v1/cash-registers/{id}/open`**
  **Objetivo:** abrir caixa pela API. **Depende:** 606
  **Implementar:** endpoint idempotente + resposta com sessão criada; vínculo da sessão autenticada ao caixa (atualiza `auth_sessions.cash_register_id`).
  **Testes/aceite:** 201; 409 já aberto; 403 sem `cash.open`; repetir com mesma `Idempotency-Key` devolve a mesma sessão.
  **Commit:** `feat(cash): expoe abertura de caixa na API`

- [x] **607b — FK de `auth_sessions.cash_register_id` + validação do caixa no login**
  **Objetivo:** garantir no banco o vínculo da sessão com o caixa e recusar caixa inexistente ou inativo no login. **Depende:** 601, 607
  **Implementar:** `V15__auth_sessions_cash_register_fk.sql` (limpa vínculos órfãos pré-FK de dev/teste e cria a constraint com `on delete restrict`); porta `CashRegisterLookup` em `shared/application` — inversão que evita o ciclo `auth → cash` — implementada pelo repositório de caixa; `FieldValidationException` + `errors[]` no `BusinessExceptionMapper`; `LoginUseCase` valida o caixa depois da senha e antes de gravar (nulo segue aceito).
  **Testes/aceite:** constraint existe com `on delete restrict`; insert com caixa inexistente → SQLState 23503; login com caixa desconhecido/inativo → 400 `VALIDATION_ERROR` com `errors[0].field == "cashRegisterId"`; login com `CAIXA-01` → 200 e `/auth/me` mostra o caixa.
  **Commit:** `feat(auth): valida caixa ativo no login e cria a FK de auth_sessions`

- [x] **608 — API `GET .../current-session`**
  **Objetivo:** TUI mostra o caixa aberto. **Depende:** 607
  **Implementar:** sessão atual com totais por tipo de movimento e saldo esperado; 404 quando não há sessão aberta.
  **Testes/aceite:** valores conferem após abrir + sangria + suprimento.
  **Commit:** `feat(cash): consulta sessao de caixa atual`

- [x] **609 — Caso de uso de sangria**
  **Objetivo:** retirar dinheiro com rastro. **Depende:** 605
  **Implementar:** valida sessão aberta, valor > 0, motivo obrigatório; permissão `cash.withdrawal`; movimento negativo; auditoria `CASH_WITHDRAWAL` (valor, motivo, saldo esperado antes/depois); alerta (não bloqueio) se valor > saldo esperado.
  **Testes/aceite:** sucesso registra movimento e auditoria; OPERADOR sem permissão → 403; motivo vazio → 400.
  **Commit:** `feat(cash): implementa sangria`

- [x] **610 — Caso de uso de suprimento + endpoints**
  **Objetivo:** colocar dinheiro no caixa. **Depende:** 609
  **Implementar:** `POST /cash-registers/{id}/withdrawals` e `POST /cash-registers/{id}/supplies` (idempotentes), permissão `cash.supply`, auditoria `CASH_SUPPLY`.
  **Testes/aceite:** 201 e saldo esperado atualizado; replay idempotente não duplica movimento.
  **Commit:** `feat(cash): implementa suprimento e expoe sangria/suprimento`

- [x] **611 — Caso de uso `CloseCashSession`**
  **Objetivo:** fechar caixa com conferência. **Depende:** 605, 604
  **Implementar:** lock da sessão; valida que não há vendas `OPEN` (consulta preparada para a Fase 8); grava `counted_amount`, `expected_amount`, `difference_amount`, `closing_notes`; status `CLOSED`; auditoria `CASH_SESSION_CLOSED`; permissão `cash.close`.
  **Testes/aceite:** fecha com diferença calculada corretamente; fechar duas vezes → 409; auditoria registrada.
  **Commit:** `feat(cash): implementa fechamento de caixa`

- [x] **612 — API de fechamento e resumo**
  **Objetivo:** fechar e conferir pela API. **Depende:** 611
  **Implementar:** `POST /cash-registers/{id}/close` (idempotente) + `GET /cash-sessions/{id}` + `GET /cash-sessions/{id}/summary` (esperado × contado, por tipo de movimento).
  **Testes/aceite:** 200; resumo confere com os movimentos; 409 se já fechada.
  **Commit:** `feat(cash): expoe fechamento e resumo de caixa`

- [x] **613 — Testes de concorrência de caixa**
  **Objetivo:** provar as garantias de §8. **Depende:** 612
  **Implementar:** testes com threads: dois `open` simultâneos (1 sucesso, 1 conflito); dois `close` simultâneos (1 sucesso); sangria durante fechamento não corrompe totais.
  **Testes/aceite:** 3 testes verdes e determinísticos (usar `ExecutorService` + latch, sem `sleep` arbitrário).
  **Commit:** `test(cash): cobre concorrencia de abertura e fechamento`

- [x] **614 — Autorização e auditoria do caixa**
  **Objetivo:** fechar o módulo. **Depende:** 606–612
  **Implementar:** suíte de permissões (`cash.open`, `cash.close`, `cash.withdrawal`, `cash.supply`) e auditoria de todas as operações.
  **Testes/aceite:** OPERADOR abre/fecha e não faz sangria; GERENTE faz sangria; eventos conferidos.
  **Commit:** `test(cash): cobre permissoes e auditoria do caixa`

---

## Fase 7 — Estoque

- [x] **701 — Migration `product_stocks` + `stock_movements`**
  **Objetivo:** saldo e ledger. **Depende:** 403
  **Implementar:** `V16__stock.sql` conforme §5.3 (ledger com `balance_after`, único `(store_id, product_id)` no saldo) + grants sem `UPDATE`/`DELETE` em `stock_movements`.
  **Testes/aceite:** migration aplica; `UPDATE`/`DELETE` no ledger falham por permissão.
  **Commit:** `feat(inventory): cria saldo e ledger de estoque`

- [x] **702 — Repositórios de estoque**
  **Objetivo:** acessar saldo e ledger. **Depende:** 701
  **Implementar:** `ProductStockRepository` (`findByProduct`, `lockByProduct` com `FOR UPDATE`, `insertIfAbsent`, `updateQuantity`) e `StockMovementRepository` (`insert`, `listByProduct`, `sumByType`).
  **Testes/aceite:** integração: lock bloqueia segunda transação; saldo criado sob demanda.
  **Commit:** `feat(inventory): adiciona repositorios de estoque`

- [x] **703 — `StockService.applyMovement`**
  **Objetivo:** única porta de alteração de saldo. **Depende:** 702
  **Implementar:** aplica delta com lock pessimista na linha de saldo, grava movimento com `balance_after` e `reference_type/id`; se `allow_negative_stock=false` e o saldo ficaria negativo → `422 INSUFFICIENT_STOCK`; **ordenação por `product_id`** para múltiplos itens (evita deadlock).
  **Testes/aceite:** unitário/integração: entrada, saída, saldo insuficiente bloqueado, saldo insuficiente permitido com flag, `balance_after` correto em sequência de movimentos.
  **Commit:** `feat(inventory): aplica movimentos de estoque com lock`

- [x] **704 — Consulta de estoque**
  **Objetivo:** ver saldos. **Depende:** 702
  **Implementar:** `GET /api/v1/stock` (busca por nome/barcode, filtro `lowStock` usando `min_quantity`, paginação) e `GET /api/v1/stock/{productId}` (saldo + últimos movimentos); permissão `stock.read`.
  **Testes/aceite:** saldo correto após movimentos; filtro de estoque baixo funciona.
  **Commit:** `feat(inventory): consulta saldos e movimentos`

- [x] **705 — Ajuste manual de estoque**
  **Objetivo:** corrigir divergência com rastro. **Depende:** 703
  **Implementar:** `POST /stock/{productId}/adjustments` `{quantityDelta, reason}`; permissão `stock.adjust`; motivo obrigatório; auditoria `STOCK_ADJUSTED` com saldo antes/depois.
  **Testes/aceite:** ajuste positivo/negativo grava movimento e auditoria; OPERADOR → 403; motivo vazio → 400.
  **Commit:** `feat(inventory): permite ajuste manual de estoque`

- [x] **706 — Entrada de mercadoria**
  **Objetivo:** repor estoque com custo. **Depende:** 705
  **Implementar:** `POST /stock/{productId}/receipts` `{quantity, unitCost, reason}` → movimento `PURCHASE_IN`, atualização opcional de `cost_price`; permissão `stock.receive`; auditoria.
  **Testes/aceite:** saldo sobe; custo atualizado quando informado; auditoria registrada.
  **Commit:** `feat(inventory): registra entrada de mercadoria`

- [x] **707 — Testes de concorrência de estoque**
  **Objetivo:** provar consistência sob disputa. **Depende:** 703
  **Implementar:** testes: 20 ajustes simultâneos de −1 em saldo 20 → saldo final 0 e 20 movimentos coerentes; dois produtos em ordem invertida não geram deadlock (ordenação).
  **Testes/aceite:** testes verdes e determinísticos; nenhum deadlock detectado.
  **Commit:** `test(inventory): cobre concorrencia de movimentos de estoque`

---

## Fase 8 — Vendas (venda aberta, itens, desconto)

- [x] **801 — Migrations de venda**
  **Objetivo:** estrutura da venda. **Depende:** 603, 403
  **Implementar:** `V17__document_sequences.sql`, `V18__sales.sql`, `V19__sale_items.sql` conforme §5.3.
  **Testes/aceite:** migrations aplicam; unique `(store_id, number)`; `line_number` único por venda.
  **Commit:** `feat(sales): cria tabelas de vendas e itens`

- [x] **802 — Domínio `Sale`/`SaleItem`**
  **Objetivo:** regras de venda em código puro. **Depende:** 801
  **Implementar:** agregado com `addItem`, `changeQuantity`, `removeItem`, `applyDiscount`, `recalculate`, `isPaidBy(payments)`, `complete()`; snapshot de preço/nome/unidade; arredondamento HALF_UP (BR-01/02/03).
  **Testes/aceite:** 12+ testes unitários: totais, desconto percentual e por valor, desconto não deixa total negativo, alteração de preço do produto não afeta item existente, venda imutável após concluir.
  **Commit:** `feat(sales): adiciona modelo de dominio da venda`

- [x] **803 — Persistência da venda**
  **Objetivo:** salvar/carregar agregado completo. **Depende:** 802
  **Implementar:** `SaleEntity`/`SaleItemEntity` + mapper + `SaleRepository` (`findById` com itens, `insert`, `update`, `search` com filtros, `lockById`, `existsOpenByCashSession`).
  **Testes/aceite:** round-trip agregado→banco→agregado preserva totais e itens; busca por período/status.
  **Commit:** `feat(sales): adiciona persistencia de vendas`

- [x] **804 — Alocador de número da venda**
  **Objetivo:** número sequencial por loja. **Depende:** 801
  **Implementar:** `SaleNumberAllocator` usando `document_sequences` com `UPDATE ... RETURNING next_value`; inicialização automática da linha.
  **Testes/aceite:** 50 alocações concorrentes → 50 números únicos e sem buraco (teste com threads).
  **Commit:** `feat(sales): aloca numeracao sequencial de venda`

- [x] **805 — Caso de uso `CreateSale`**
  **Objetivo:** abrir venda no caixa da sessão. **Depende:** 803, 804, 604
  **Implementar:** exige sessão autenticada vinculada a caixa com sessão de caixa `OPEN` (BR-06/BR-11); cria venda `OPEN` vazia com número; auditoria `SALE_CREATED`.
  **Testes/aceite:** cria com sucesso; sem caixa aberto → 409 `CASH_SESSION_REQUIRED`; sem vínculo de caixa → 403.
  **Commit:** `feat(sales): implementa abertura de venda`

- [x] **806 — Idempotência na API**
  **Objetivo:** retry não duplica operação. **Depende:** 801, 607a
  **Implementar:** reutiliza o mecanismo do **607a** nos endpoints de dinheiro/estoque — `idempotency_keys` (V14), `IdempotencyService` e `IdempotencyGuard`: hash do corpo, replay da resposta, 409 se mesma chave com corpo diferente. A `V17__idempotency_keys.sql` que o passo citava não existe mais; sem migration nova.
  **Testes/aceite:** duas chamadas com a mesma chave → mesma resposta e um único efeito; corpo diferente → 409 `IDEMPOTENCY_KEY_REUSED`; sem header em endpoint obrigatório → 400.
  **Commit:** `docs(shared): registra verificacao da idempotencia na API` (mecanismo veio no 607a)

- [x] **807 — API `POST /api/v1/sales`**
  **Objetivo:** abrir venda pela API/TUI. **Depende:** 805, 806
  **Implementar:** endpoint com `Idempotency-Key` obrigatória; resposta com número, status e totais zerados.
  **Testes/aceite:** 201; replay idempotente; 403 sem `sale.create`.
  **Commit:** `feat(sales): expoe abertura de venda`

- [x] **808 — Caso de uso `AddSaleItem`**
  **Objetivo:** bipe vira item. **Depende:** 802, 404
  **Implementar:** resolve produto por `barcode` **bruto** (GTIN, código interno ou etiqueta de balança — BR-14) ou por `productId`; recusa produto inativo (422 `PRODUCT_INACTIVE`); soma quantidade se o item já existe (regra definida: sim, soma); snapshot de preço; recalcula totais; auditoria `SALE_ITEM_ADDED`.
  **Testes/aceite:** adiciona item novo; soma item repetido; produto inexistente → 404; venda concluída → 409 `SALE_NOT_OPEN`.
  **Commit:** `feat(sales): adiciona itens a venda`

- [x] **809a — Guarda de posse e casos de uso de item**
  **Objetivo:** alterar e remover item, com a venda só acessível pela sessão de caixa dona (BR-11, §9.4). **Depende:** 808
  **Implementar:** `SaleAccessGuard.requireOwned(saleId, cashRegisterId)` em `sales/application` (venda inexistente → 404 `SALE_NOT_FOUND`; caixa da sessão nulo ou diferente do caixa da venda → 403 `ACCESS_DENIED`) e `requireOpen` (409 `SALE_NOT_OPEN`); `AddSaleItemCommand` ganha `cashRegisterId` e o caso de uso do 808 passa pela guarda; `ChangeSaleItemQuantityUseCase` (item fora da venda → 404 `SALE_ITEM_NOT_FOUND`, código novo) e `RemoveSaleItemUseCase`, ambos com `findById` + `update` (mesmo caminho otimista do 808) e auditoria `SALE_ITEM_QUANTITY_CHANGED`/`SALE_ITEM_REMOVED` na mesma transação.
  **Testes/aceite:** unitários dos dois casos de uso novos e da posse no `AddSaleItem`: 200/estado com totais recalculados e evento; 403 venda de outro caixa; 404 venda; 404 item; 409 venda concluída.
  **Commit:** `feat(sales): adiciona casos de uso de item com posse`

- [x] **809b — API de itens**
  **Objetivo:** manipular itens via HTTP. **Depende:** 809a
  **Implementar:** `POST /sales/{id}/items`, `PATCH /sales/{id}/items/{itemId}`, `DELETE /sales/{id}/items/{itemId}` (200 com `SaleDetailResponse` — id, número, status, caixa/sessão/operador, cliente, totais, desconto, `itemCount`, timestamps e `items[]` —, `{itemId}` = `productId`), DTOs `SaleItemRequest`/`SaleItemQuantityRequest`/`SaleItemResponse`, permissão `sale.create` nas três e as 3 rotas em `API_ROUTES`.
  **Testes/aceite:** 200 com totais recalculados nos três; 403 em venda de outro caixa; 404 item inexistente; 409 venda concluída; 400 forma inválida; 401 sem token nas 3 rotas.
  **Commit:** `feat(sales): expoe inclusao, alteracao e remocao de itens`

- [x] **810 — Caso de uso `ApplyDiscount`**
  **Objetivo:** desconto com controle. **Depende:** 802, 305
  **Implementar:** permissão `sale.discount.apply`, motivo obrigatório, limite `max_discount_percent` da loja, tipos `VALUE`/`PERCENT`; auditoria `SALE_DISCOUNT_APPLIED` com valor e motivo; remoção do desconto.
  **Testes/aceite:** aplica percentual e valor; acima do limite → 403/422; OPERADOR → 403; remover desconto volta ao subtotal.
  **Commit:** `feat(sales): aplica desconto com permissao e motivo`

- [x] **811a — Domínio e casos de uso do vínculo de cliente**
  **Objetivo:** vincular e desvincular cliente na venda aberta. **Depende:** 810, 502
  **Implementar:** `Sale.linkCustomer(UUID)`/`unlinkCustomer()` com guarda de venda aberta; `customer_id` no `SaleEntity`/`SaleMapper` (rehidrata na ordem itens → desconto → cliente → `complete()`); `CustomerStore.findAnyById` (enxerga o desativado, como o `ProductStore.findById`) + teste de integração; `LinkCustomerUseCase`/`UnlinkCustomerUseCase` (+ commands) com a guarda do 809, `findById` + `update` e auditoria `SALE_CUSTOMER_LINKED`/`SALE_CUSTOMER_UNLINKED` na mesma transação (desvincular sem vínculo é no-op, como a remoção de desconto do 810); cliente inexistente → 404 `CUSTOMER_NOT_FOUND` e inativo → 422 `CUSTOMER_INACTIVE` (código novo). Passo dividido do 811 original (diff estimado acima de ~300 linhas); o restante é o 811b.
  **Testes/aceite:** unitários de link/unlink (404/422/409/403, no-op sem vínculo, auditoria) e do domínio (`linkCustomer`/`unlinkCustomer`, imutável após concluída); integração: link grava `customer_id` e o round-trip `findById` restaura o cliente.
  **Commit:** `feat(sales): vincula cliente a venda`

- [x] **811b — API de desconto e cliente**
  **Objetivo:** expor desconto e vínculo de cliente. **Depende:** 811a
  **Implementar:** `PUT/DELETE /sales/{id}/discount` (`sale.discount.apply`) e `PUT/DELETE /sales/{id}/customer` (`sale.create`), os quatro com 200 e o `SaleDetailResponse`, que ganha `discountType`, `discountValue` e `discountReason`; 4 rotas novas em `API_ROUTES`.
  **Testes/aceite:** 200 nos quatro endpoints; cliente inativo → 422; cliente inexistente → 404; OPERADOR → 403 nos dois de desconto (matriz real); venda de outro caixa → 403; venda concluída → 409; forma inválida → 400.
  **Commit:** `feat(sales): expoe desconto e cliente na venda`

- [x] **812 — Consulta de vendas**
  **Objetivo:** ver venda e histórico. **Depende:** 803
  **Implementar:** `GET /sales/{id}` (itens + pagamentos + desconto + status) e `GET /sales` (filtros `from`, `to`, `status`, `cashSessionId`, `operatorUserId`, paginação).
  **Testes/aceite:** detalhe correto; filtros e paginação funcionando; venda de outro caixa → 403 para operador.
  **Commit:** `feat(sales): consulta venda e historico`

- [x] **813 — Cancelamento de venda aberta**
  **Objetivo:** desistir da venda sem sujeira. **Depende:** 805
  **Implementar:** `POST /sales/{id}/cancel` `{reason}` (idempotente); só `OPEN`; status `CANCELLED` + motivo + autor; auditoria `SALE_CANCELLED`; permissão `sale.cancel`.
  **Testes/aceite:** 200; venda concluída → 409 `SALE_ALREADY_COMPLETED`; motivo obrigatório; auditoria.
  **Commit:** `feat(sales): cancela venda aberta`

- [x] **814a — Concorrência de vendas**
  **Objetivo:** provar o lock otimista da venda sob disputa. **Depende:** 808–813
  **Implementar:** `SaleConcurrencyTest` (PostgreSQL real, `ExecutorService` + `CountDownLatch`, sem `sleep`): determinístico — thread com transação própria lê a venda e segura enquanto o vencedor comita; a inclusão vencida, na mesma transação da leitura, vira 409 `CONCURRENT_MODIFICATION` sem escrita parcial (item do vencedor intacto, item do perdedor ausente); corrida real de K rodadas de dois `addItem` simultâneos na mesma venda e no mesmo produto — nunca os dois falham, toda falha é `CONCURRENT_MODIFICATION`, quantidade final = quantidade × sucessos, um `SALE_ITEM_ADDED` por sucesso e nenhum item perdido. Passo dividido do 814 original (diff estimado acima de ~300 linhas); a auditoria é o 814b.
  **Testes/aceite:** testes verdes e repetidos 5×; nenhum item perdido.
  **Commit:** `test(sales): cobre concorrencia de vendas`

- [x] **814b — Auditoria de vendas**
  **Objetivo:** fechar o módulo com o rastro completo. **Depende:** 814a
  **Implementar:** suíte de auditoria do fluxo real da API — `SALE_CREATED`, `SALE_ITEM_ADDED`, `SALE_ITEM_QUANTITY_CHANGED`, `SALE_ITEM_REMOVED`, `SALE_DISCOUNT_APPLIED`, `SALE_DISCOUNT_REMOVED`, `SALE_CUSTOMER_LINKED`, `SALE_CUSTOMER_UNLINKED` e `SALE_CANCELLED` — conferindo `action` + `entity_id` + `details` essenciais, um evento por operação, nunca contagem global (padrão do `CashPermissionsAuditTest`).
  **Testes/aceite:** um evento por operação do fluxo, no alvo e com os details essenciais; sem evento nas tentativas barradas.
  **Commit:** `test(sales): cobre concorrencia e auditoria de vendas`

---

## Fase 9 — Pagamentos e conclusão

- [x] **901 — Migration `payments`**
  **Objetivo:** pagamentos por venda. **Depende:** 801
  **Implementar:** `V20__payments.sql` conforme §5.3 (múltiplos pagamentos, `tendered_amount`, `change_amount`, status).
  **Testes/aceite:** migration aplica; `amount > 0` garantido por check.
  **Commit:** `feat(sales): cria tabela de pagamentos`

- [x] **902 — Domínio de pagamento**
  **Objetivo:** regras de pagamento puras. **Depende:** 901
  **Implementar:** `Payment` + regras: soma de aprovados, troco apenas para `CASH` (`tendered − amount`), métodos válidos, `paidAmount`/`changeAmount` da venda, `isFullyPaid`.
  **Testes/aceite:** 8+ testes unitários (pagamento único, múltiplos, troco, insuficiente, pagamento maior que o total em cartão → recusado ou registrado conforme regra definida).
  **Commit:** `feat(sales): adiciona modelo de dominio de pagamento`

- [x] **903 — Persistência de pagamento**
  **Objetivo:** salvar pagamentos. **Depende:** 902
  **Implementar:** `PaymentEntity` + mapper + repo (`listBySale`, `insert`, `cancel`, `sumApprovedBySale`).
  **Testes/aceite:** round-trip e soma correta.
  **Commit:** `feat(sales): adiciona persistencia de pagamentos`

- [x] **904 — Caso de uso `AddPayment`**
  **Objetivo:** receber pagamento. **Depende:** 903, 802
  **Implementar:** venda `OPEN` e do próprio caixa; `amount > 0`; em dinheiro exige `tenderedAmount ≥ amount`; calcula troco; recalcula `paidAmount`; auditoria `PAYMENT_ADDED`; permissão `payment.add`.
  **Testes/aceite:** pagamento parcial; pagamento que completa; dinheiro com troco; tendered insuficiente → 422; venda de outro caixa → 403.
  **Commit:** `feat(sales): registra pagamentos`

- [x] **905 — API de pagamentos**
  **Objetivo:** pagar pela API. **Depende:** 904
  **Implementar:** `POST /sales/{id}/payments` (idempotente) e `DELETE /sales/{id}/payments/{paymentId}` (cancelar pagamento antes da conclusão).
  **Testes/aceite:** 201 com troco; replay idempotente não duplica; cancelamento recalcula `paidAmount`.
  **Commit:** `feat(sales): expoe pagamentos da venda`

- [x] **906 — Caso de uso `CompleteSale`** ⭐ núcleo do sistema
  **Objetivo:** concluir venda movendo estoque, caixa e auditoria na mesma transação. **Depende:** 905, 703, 611
  **Implementar:** valida `paidAmount ≥ total` (BR-05); obtém lock das linhas de estoque **ordenadas por `product_id`**; grava `SALE_OUT` por item via `StockService`; grava movimento `SALE` no caixa para pagamentos em dinheiro; status `COMPLETED` + `completed_at`; auditoria `SALE_COMPLETED` com totais e formas de pagamento; idempotente por estado e por chave.
  **Testes/aceite:** conclusão move estoque e caixa corretamente; pagamento insuficiente → 422 `PAYMENT_INSUFFICIENT`; concluir duas vezes → 409/replay; rollback total quando qualquer item falha.
  **Commit:** `feat(sales): conclui venda com baixa de estoque e caixa`

- [x] **907 — API `POST /api/v1/sales/{id}/complete`**
  **Objetivo:** concluir pela API. **Depende:** 906
  **Implementar:** endpoint idempotente com resposta completa (totais, troco, número).
  **Testes/aceite:** 200; replay devolve a mesma resposta com `Idempotency-Replayed: true` e **sem** segunda baixa de estoque (assertar saldo).
  **Commit:** `feat(sales): expoe conclusao de venda`

- [x] **908 — Testes de concorrência da conclusão**
  **Objetivo:** provar §8 sob disputa. **Depende:** 906
  **Implementar:** última unidade com duas vendas simultâneas (`allow_negative_stock=false` → uma falha; `true` → saldo negativo auditado); dois pagamentos simultâneos na mesma venda.
  **Testes/aceite:** testes determinísticos verdes; saldo final e ledger coerentes.
  **Commit:** `test(sales): cobre concorrencia de conclusao de venda`

- [x] **909 — Fechamento de caixa com vendas**
  **Objetivo:** esperado do caixa reflete as vendas. **Depende:** 906, 611
  **Implementar:** incluir movimentos `SALE` em dinheiro no `expectedAmount`; `GET /cash-sessions/{id}/summary` com quebra por forma de pagamento; teste: abrir → 3 vendas (2 dinheiro, 1 cartão) → esperado = abertura + dinheiro − sangrias.
  **Testes/aceite:** valores batem com a soma dos movimentos; venda em cartão não afeta dinheiro esperado.
  **Commit:** `feat(cash): considera vendas no fechamento do caixa`

- [x] **910 — Teste de fluxo completo (API)**
  **Objetivo:** provar o MVP ponta a ponta no backend. **Depende:** 907, 909
  **Implementar:** teste de integração único: login → abrir caixa → criar venda → 3 itens (barcode) → desconto → 2 pagamentos → conclusão → conferir estoque, movimento de caixa e trilha de auditoria completa.
  **Testes/aceite:** teste verde e legível (documenta o fluxo real); serve de referência para TUI e Web.
  **Commit:** `test(sales): cobre fluxo completo de venda ponta a ponta`

---

## Fase 10 — Auditoria (consulta e garantias)

- [x] **1001 — Consulta de auditoria**
  **Objetivo:** investigar operações. **Depende:** 303
  **Implementar:** `GET /api/v1/audit-events` com filtros (`entityType`, `entityId`, `actorUserId`, `action`, `cashSessionId`, `from`, `to`) + paginação; permissão `audit.read`.
  **Testes/aceite:** filtros combinados funcionam; OPERADOR → 403; ordenação decrescente por `occurred_at`.
  **Commit:** `feat(audit): expoe consulta de auditoria`

- [x] **1002 — Histórico por entidade**
  **Objetivo:** reconstruir a vida de uma venda. **Depende:** 1001
  **Implementar:** consulta por `entityType`+`entityId` retornando linha do tempo legível; usar no detalhe da venda (futuro link no Web).
  **Testes/aceite:** venda com 6 eventos aparece em ordem cronológica com ator e motivo.
  **Commit:** `feat(audit): adiciona historico por entidade`

- [x] **1003 — Índices e desempenho da auditoria**
  **Objetivo:** consulta rápida com volume. **Depende:** 1001
  **Implementar:** validar índices de §5.3 com `EXPLAIN` em base com 100 k eventos; ajustar se necessário.
  **Testes/aceite:** consultas por entidade e por período usam índice (sem seq scan).
  **Commit:** `perf(audit): valida indices da consulta de auditoria`

- [ ] **1004 — Limpeza de chaves de idempotência**
  **Objetivo:** evitar crescimento infinito. **Depende:** 806
  **Implementar:** job agendado (`@Scheduled`) diário removendo `idempotency_keys` expiradas (24 h) + teste com relógio controlado.
  **Testes/aceite:** chave expirada é removida; chave válida permanece.
  **Commit:** `chore(shared): limpa chaves de idempotencia expiradas`

- [ ] **1005 — Catálogo de auditoria documentado**
  **Objetivo:** consulta sem adivinhação. **Depende:** 1001
  **Implementar:** `docs/auditoria.md` com a lista de `action`, campos de `details` e exemplo de investigação (venda, desconto, sangria).
  **Testes/aceite:** documento cobre todos os eventos emitidos no código (conferido por busca no fonte).
  **Commit:** `docs(audit): documenta catalogo de eventos de auditoria`

---

## Fase 11 — TUI (PDV)

- [ ] **1101 — Projeto da TUI**
  **Objetivo:** esqueleto TypeScript + Ink funcionando. **Depende:** 001
  **Implementar:** npm workspaces na raiz; `terminal/` com TypeScript strict, Ink 7, Vitest, ESLint; `npm run dev` mostra um "hello" e encerra com `q`.
  **Testes/aceite:** `npm test` e `tsc --noEmit` verdes; execução manual no Windows e no Linux.
  **Commit:** `chore(tui): cria projeto da TUI com Ink`

- [ ] **1102 — Client de API tipado**
  **Objetivo:** falar com o backend sem duplicar tipos. **Depende:** 1101, 907
  **Implementar:** `packages/api-client` gerado do OpenAPI (`openapi-typescript`) + wrapper `fetch` com bearer token, `Idempotency-Key` automática, timeout, retry seguro (só leitura) e tradução de `problem+json` para `ApiError`.
  **Testes/aceite:** testes com servidor fake: sucesso, 401, 409 com `code`, timeout, retry de GET e **não** retry de POST.
  **Commit:** `feat(tui): adiciona client de API tipado`

- [ ] **1103 — Núcleo da TUI: máquina de estados**
  **Objetivo:** lógica pura e testável. **Depende:** 1101
  **Implementar:** `core/state.ts` (união discriminada: `Login`, `OpeningCash`, `SaleOpen`, `Paying`, `ClosingCash`, `Error`) + `core/reducer.ts` (transições puras a partir de ações: bipe, tecla, resposta da API).
  **Testes/aceite:** 15+ testes unitários das transições, sem renderização.
  **Commit:** `feat(tui): adiciona maquina de estados da operacao`

- [ ] **1104a — Núcleo: leitor de código de barras**
  **Objetivo:** bipe confiável independente de foco. **Depende:** 1103
  **Implementar:** `core/scanner.ts`: acumula caracteres com intervalo < 50 ms, encerra em `ENTER`/`TAB`, emite o `barcode` **bruto** (a interpretação é do servidor, BR-14); descarta digitação humana lenta; funciona em qualquer tela exceto modais bloqueantes; multiplicador `3*` + bipe vira `quantity = 3`.
  **Testes/aceite:** rajada rápida vira um barcode; digitação lenta não; `ENTER` isolado não; `3*` + bipe resulta em quantidade 3.
  **Commit:** `feat(tui): adiciona captura de codigo de barras`

- [ ] **1104b — Etiqueta de balança (backend + TUI)**
  **Objetivo:** vender a granel com etiqueta impressa pela balança. **Depende:** 1104a, 413
  **Implementar:** configuração por loja (`internal_barcode_prefix`, tamanho do código interno, campo embutido `WEIGHT`/`PRICE`, casas decimais) + coluna `internal_code` em `products` (migration aditiva); resolução e cálculo de quantidade **no servidor** (BR-14); TUI apenas envia a string bruta.
  **Testes/aceite:** etiqueta de peso gera item em kg com total correto; etiqueta de preço gera total igual ao embutido (tolerância R$ 0,01); código inválido → 422 `INVALID_INTERNAL_BARCODE`; testes com etiquetas reais das balanças da loja.
  **Commit:** `feat(catalog): interpreta etiqueta de balanca no servidor`

- [ ] **1104c — Autoteste do leitor (F11)**
  **Objetivo:** diagnosticar o leitor sem chamar suporte técnico. **Depende:** 1104a
  **Implementar:** tela `F11` com último código lido, intervalo entre caracteres, interpretação aplicada (GTIN/código interno/balança) e instruções de configuração do equipamento (sufixo `ENTER`, prefixo, simbologias, layout de teclado).
  **Testes/aceite:** tela exibe leitura e timing; `docs/leitores.md` com o guia de configuração dos modelos usados na loja.
  **Commit:** `feat(tui): adiciona autoteste do leitor de codigo de barras`

- [ ] **1105 — Núcleo: atalhos de teclado**
  **Objetivo:** operação 100% por teclado. **Depende:** 1103
  **Implementar:** `core/keys.ts` com o mapa de §11.3 (F1–F12, ENTER, ESC, setas, `+`/`-`, DEL) e resolução de conflito por contexto.
  **Testes/aceite:** cada atalho resolve a ação esperada no contexto correto; ESC fecha modal antes de sair da tela.
  **Commit:** `feat(tui): mapeia atalhos de teclado`

- [ ] **1106 — Tela de login e seleção de caixa**
  **Objetivo:** operador entra no PDV. **Depende:** 1102, 1103
  **Implementar:** formulário usuário/senha (senha mascarada), seleção do caixa (`GET /cash-registers`), tratamento de erro de credencial e de bloqueio; guarda token apenas em memória.
  **Testes/aceite:** `ink-testing-library`: login OK navega para venda; credencial inválida mostra erro sem sair da tela.
  **Commit:** `feat(tui): adiciona tela de login e selecao de caixa`

- [ ] **1107 — Abertura de caixa**
  **Objetivo:** informar valor inicial. **Depende:** 1106
  **Implementar:** tela de abertura quando o caixa não tem sessão; campo de valor com máscara; `409` já aberto → segue para a venda usando a sessão existente (com aviso).
  **Testes/aceite:** abre e navega; valor inválido não envia; caixa já aberto exibe aviso e permite continuar.
  **Commit:** `feat(tui): adiciona abertura de caixa`

- [ ] **1108 — Tela de venda (layout)**
  **Objetivo:** tela principal do operador. **Depende:** 1107
  **Implementar:** cabeçalho (loja, caixa, operador, hora), lista de itens com último destacado, painel de totais (subtotal, desconto, total) e barra de status com atalhos.
  **Testes/aceite:** snapshot de render com 0, 1 e 20 itens; largura mínima 80×24 sem quebrar.
  **Commit:** `feat(tui): monta tela de venda`

- [ ] **1109 — Fluxo: bipe adiciona item**
  **Objetivo:** operação principal funcionando. **Depende:** 1108, 1104, 809
  **Implementar:** bipe → `POST /sales/{id}/items`; cria a venda na primeira leitura; item não encontrado mostra aviso e oferece cadastro rápido (se tiver permissão — cadastro rápido é SHOULD, aqui só o aviso); feedback visual + som (bell).
  **Testes/aceite:** bipe adiciona e soma quantidade; não encontrado mostra aviso e não quebra a venda; erro de rede mantém estado e permite retry.
  **Commit:** `feat(tui): adiciona itens por codigo de barras`

- [ ] **1110 — Fluxo: alterar quantidade e remover item**
  **Objetivo:** corrigir a venda. **Depende:** 1109
  **Implementar:** `+`/`-` no item selecionado, `DEL` remove (com confirmação), setas navegam.
  **Testes/aceite:** quantidade e totais atualizam; remover zera item; confirmação evita remoção acidental.
  **Commit:** `feat(tui): permite alterar quantidade e remover itens`

- [ ] **1111 — Fluxo: desconto (F5)**
  **Objetivo:** aplicar desconto com motivo. **Depende:** 1110, 811
  **Implementar:** modal com tipo (valor/percentual), valor e motivo; erros de permissão e de limite exibidos claramente.
  **Testes/aceite:** desconto aplicado reflete nos totais; OPERADOR sem permissão vê mensagem clara (403 tratado); ESC cancela sem alterar.
  **Commit:** `feat(tui): aplica desconto com motivo`

- [ ] **1112 — Fluxo: cliente na venda (F6)**
  **Objetivo:** vincular CPF/nome. **Depende:** 1111, 502
  **Implementar:** busca por nome/CPF com lista de resultados; vincular/remover.
  **Testes/aceite:** busca retorna resultados; vínculo aparece no cabeçalho; remover limpa.
  **Commit:** `feat(tui): vincula cliente a venda`

- [ ] **1113 — Fluxo: pagamento e conclusão (F9)** ⭐
  **Objetivo:** fechar a venda. **Depende:** 1112, 907
  **Implementar:** modal de pagamento com método (dinheiro/PIX/débito/crédito/voucher), valor e valor recebido; mostra troco em destaque; múltiplos pagamentos; conclusão → tela de sucesso com número, total e troco; `ENTER` inicia próxima venda.
  **Testes/aceite:** pagamento parcial, múltiplos pagamentos, troco correto, pagamento insuficiente bloqueia conclusão, replay idempotente não duplica venda.
  **Commit:** `feat(tui): implementa pagamento e conclusao de venda`

- [ ] **1114 — Fluxo: sangria e suprimento (F7/F8)**
  **Objetivo:** movimentar dinheiro do caixa. **Depende:** 1113, 610
  **Implementar:** modais com valor e motivo; confirmação; exibe saldo esperado atualizado.
  **Testes/aceite:** sangria reduz e suprimento aumenta o esperado; motivo obrigatório; sem permissão mostra erro claro.
  **Commit:** `feat(tui): adiciona sangria e suprimento`

- [ ] **1115 — Fluxo: fechamento de caixa (F10)**
  **Objetivo:** encerrar o turno. **Depende:** 1114, 909
  **Implementar:** resumo (esperado por forma de pagamento, sangrias, suprimentos), campo de valor contado, exibição da diferença, confirmação e logout opcional.
  **Testes/aceite:** diferença calculada corretamente; bloqueio quando há venda aberta (409 tratado); após fechar volta ao login.
  **Commit:** `feat(tui): implementa fechamento de caixa`

- [ ] **1116 — Consulta de preço (F2) e ajuda (F1)**
  **Objetivo:** consultar sem vender e aprender os atalhos. **Depende:** 1108
  **Implementar:** F2 abre busca por barcode/nome mostrando preço e estoque; F1 mostra mapa de teclas.
  **Testes/aceite:** consulta não cria venda; ajuda lista todos os atalhos ativos.
  **Commit:** `feat(tui): adiciona consulta de preco e ajuda`

- [ ] **1117 — Resiliência: rede, sessão e erros**
  **Objetivo:** o PDV não perde venda nem estado. **Depende:** 1113
  **Implementar:** tratamento central de erros por `code`; `401` → volta ao login preservando a venda aberta em memória (com aviso); `409` de idempotência → reconcilia; indicador de conexão na barra de status; nenhuma operação destrutiva silenciosa.
  **Testes/aceite:** queda de rede simulada não perde itens; reconexão retoma a venda; 401 exige novo login.
  **Commit:** `feat(tui): trata falhas de rede e de sessao`

- [ ] **1118 — Trocar operador (F12)**
  **Objetivo:** troca de turno no mesmo caixa. **Depende:** 1117
  **Implementar:** encerra a sessão atual (logout), exige nova autenticação no mesmo caixa; bloqueia troca se houver venda aberta (ou cancela após confirmação).
  **Testes/aceite:** troca funciona; venda aberta impede troca silenciosa.
  **Commit:** `feat(tui): permite troca de operador`

- [ ] **1119 — Empacotamento e execução**
  **Objetivo:** rodar no computador do caixa. **Depende:** 1118
  **Implementar:** script `npm start` com `tsx`/build; instruções de instalação (Node 22+), configuração da URL da API por variável de ambiente; atalho de inicialização documentado.
  **Testes/aceite:** execução limpa em máquina Windows; documento no README.
  **Commit:** `chore(tui): adiciona empacotamento e instrucoes de execucao`

- [ ] **1120 — Teste E2E da TUI**
  **Objetivo:** provar o PDV completo. **Depende:** 1119
  **Implementar:** teste automatizado (Vitest + backend real em docker-compose) executando: login → abrir caixa → bipe → desconto → pagamento → conclusão → fechamento; valida estoque e auditoria via API.
  **Testes/aceite:** E2E verde no CI (job separado) ou documentado como execução local obrigatória.
  **Commit:** `test(tui): cobre fluxo completo do PDV`

> 🎉 **MVP funcional a partir daqui**: backend + TUI operando o fluxo completo de venda com estoque, caixa e auditoria.

---

## Fase 12 — React Web (retaguarda) — *SHOULD HAVE*

- [ ] **1201 — Projeto web**
  **Objetivo:** SPA de administração. **Depende:** 001
  **Implementar:** Vite + React + TS + Tailwind + React Router + TanStack Query + Vitest/RTL; ESLint; layout base com navegação lateral.
  **Testes/aceite:** build, lint e teste verdes; rota inicial renderiza layout.
  **Commit:** `chore(web): cria projeto React com Vite`

- [ ] **1202 — Autenticação e guarda de rotas**
  **Objetivo:** só gente autorizada entra. **Depende:** 1201, 207
  **Implementar:** tela de login, provider de auth (token em memória + `sessionStorage`), `GET /auth/me` na inicialização, interceptor `401`, guarda de rota, logout.
  **Testes/aceite:** login navega ao dashboard; refresh mantém sessão; 401 desloga e redireciona.
  **Commit:** `feat(web): adiciona autenticacao e guarda de rotas`

- [ ] **1203 — Componentes compartilhados e erros**
  **Objetivo:** base visual única. **Depende:** 1202
  **Implementar:** `DataTable` (paginação, ordenação, vazio, loading), `Modal`, `Toast`, `usePermission`, tratamento global de `problem+json`.
  **Testes/aceite:** testes de `DataTable` e do mapeamento de erro; 403 exibe mensagem clara.
  **Commit:** `feat(web): adiciona componentes e tratamento de erros`

- [ ] **1204 — Produtos**
  **Objetivo:** manter catálogo pela retaguarda. **Depende:** 1203, 412
  **Implementar:** lista com busca/filtros, formulário de criação/edição (com `If-Match`), alteração de preço com motivo, desativar/reativar.
  **Testes/aceite:** CRUD completo na UI; conflito de versão mostra mensagem e recarrega.
  **Commit:** `feat(web): adiciona gestao de produtos`

- [ ] **1205 — Categorias**
  **Objetivo:** organizar catálogo. **Depende:** 1204
  **Implementar:** lista + formulário + desativar.
  **Testes/aceite:** CRUD na UI; erro de nome duplicado tratado.
  **Commit:** `feat(web): adiciona gestao de categorias`

- [ ] **1206 — Estoque**
  **Objetivo:** acompanhar e corrigir saldos. **Depende:** 1203, 706
  **Implementar:** lista de saldos com filtro de estoque baixo, detalhe com movimentos, ajuste e entrada de mercadoria com motivo.
  **Testes/aceite:** ajuste reflete no saldo e no histórico; permissão respeitada.
  **Commit:** `feat(web): adiciona gestao de estoque`

- [ ] **1207 — Clientes**
  **Objetivo:** manter clientes. **Depende:** 1203, 502
  **Implementar:** lista com busca, formulário, desativar.
  **Testes/aceite:** CRUD na UI; validação de CPF.
  **Commit:** `feat(web): adiciona gestao de clientes`

- [ ] **1208 — Usuários e papéis**
  **Objetivo:** administrar acessos. **Depende:** 1203, 114
  **Implementar:** lista/criação/edição de usuários, atribuição de papéis, reset de senha, desativar, revogar sessões; tela de papéis com permissões.
  **Testes/aceite:** ADMIN gerencia; GERENTE/OPERADOR não veem o menu; permissões aplicadas na UI e no servidor.
  **Commit:** `feat(web): adiciona gestao de usuarios e papeis`

- [ ] **1209 — Vendas**
  **Objetivo:** consultar e cancelar vendas. **Depende:** 1203, 813
  **Implementar:** lista com filtros de período/status/operador, detalhe com itens, pagamentos e trilha de auditoria; cancelar venda aberta; (estorno é SHOULD, passo 1301).
  **Testes/aceite:** filtros funcionam; detalhe mostra auditoria da venda.
  **Commit:** `feat(web): adiciona consulta de vendas`

- [ ] **1210 — Caixa**
  **Objetivo:** supervisionar caixas. **Depende:** 1203, 909
  **Implementar:** lista de caixas com status, sessões por período, resumo de fechamento, sangria/suprimento remoto (com permissão) e fechamento pela retaguarda.
  **Testes/aceite:** resumo confere com movimentos; ações exigem permissão correta.
  **Commit:** `feat(web): adiciona gestao de caixa`

- [ ] **1211 — Auditoria**
  **Objetivo:** investigar pela UI. **Depende:** 1203, 1002
  **Implementar:** consulta com filtros e visualização de `details` (antes/depois) e histórico por entidade.
  **Testes/aceite:** filtros combinados; OPERADOR não acessa.
  **Commit:** `feat(web): adiciona consulta de auditoria`

- [ ] **1212 — Relatórios e dashboard**
  **Objetivo:** visão do dia. **Depende:** 1203, 909
  **Implementar:** dashboard (vendas do dia, faturamento, ticket médio, formas de pagamento) + relatórios de vendas por período/operador e estoque baixo.
  **Testes/aceite:** números conferem com os dados de teste; filtros de período corretos.
  **Commit:** `feat(web): adiciona dashboard e relatorios`

- [ ] **1213 — E2E Playwright**
  **Objetivo:** provar a retaguarda. **Depende:** 1212
  **Implementar:** cenários: login → criar produto → conferir na lista; login → consultar venda concluída → ver auditoria.
  **Testes/aceite:** E2E verde contra backend real.
  **Commit:** `test(web): adiciona testes ponta a ponta`

- [ ] **1214 — Build e publicação**
  **Objetivo:** servir a SPA. **Depende:** 1213
  **Implementar:** build estático servido pelo proxy (mesma origem da API) com fallback de SPA; documentar no compose.
  **Testes/aceite:** aplicação acessível por uma origem única; API e web sem CORS.
  **Commit:** `chore(web): publica SPA com proxy de mesma origem`

---

## Fase 13 — Observabilidade e hardening

- [ ] **1301 — Métricas de negócio**
  **Objetivo:** enxergar o que importa na operação. **Depende:** 907
  **Implementar:** Micrometer com `sales_completed_total`, `sale_completion_seconds` (timer), `sale_items_total`, `login_failures_total`, `cash_session_open` (gauge); tags `store`, `method`.
  **Testes/aceite:** métricas aparecem em `/q/metrics` após o fluxo de teste.
  **Commit:** `feat(shared): adiciona metricas de negocio`

- [ ] **1302 — Health de prontidão com banco**
  **Objetivo:** orquestração confiável. **Depende:** 002
  **Implementar:** readiness com verificação do datasource; liveness independente; documentar semântica.
  **Testes/aceite:** readiness `DOWN` quando o banco cai (teste com datasource inválido) e `UP` quando volta.
  **Commit:** `feat(shared): adiciona readiness com verificacao do banco`

- [ ] **1303 — Stack de observabilidade opcional**
  **Objetivo:** visualizar métricas. **Depende:** 1301
  **Implementar:** Prometheus + Grafana no `docker-compose.observability.yml` com dashboard inicial (vendas/hora, latência de conclusão, falhas de login).
  **Testes/aceite:** dashboard carrega com dados reais do ambiente de dev.
  **Commit:** `chore(observability): adiciona Prometheus e Grafana opcionais`

- [ ] **1304 — Estorno de venda concluída**
  **Objetivo:** corrigir venda finalizada. **Depende:** 907
  **Implementar:** `POST /sales/{id}/refund` com permissão `sale.refund`: movimentos `RETURN_IN` de estoque e movimento de caixa compensatório; status permanece `COMPLETED` com registro de estorno (ou `REFUNDED`, decidir e documentar); auditoria `SALE_REFUNDED`.
  **Testes/aceite:** estoque e caixa voltam ao estado anterior; sem permissão → 403; auditoria com motivo.
  **Commit:** `feat(sales): implementa estorno de venda`

- [ ] **1305 — Proxy reverso e TLS**
  **Objetivo:** expor com segurança na loja. **Depende:** 1214
  **Implementar:** Caddy no compose (TLS interno/self-signed na LAN, headers de segurança, rate limit no `/auth/login`, compressão); remover o rate limit em memória se substituído.
  **Testes/aceite:** acesso por HTTPS; headers presentes; limite de login ativo no proxy.
  **Commit:** `chore(infra): adiciona proxy reverso com TLS`

- [ ] **1306 — Backup e restauração**
  **Objetivo:** não perder venda. **Depende:** 004
  **Implementar:** script `pg_dump` diário com retenção de 7 dias + procedimento de restore documentado e **testado** em ambiente descartável.
  **Testes/aceite:** restore em banco limpo produz base idêntica (contagens e checksum de amostra).
  **Commit:** `chore(infra): adiciona rotina de backup e restore`

- [ ] **1307 — Revisão de índices com dados reais**
  **Objetivo:** consultas quentes rápidas. **Depende:** 910
  **Implementar:** `EXPLAIN ANALYZE` nas 10 consultas mais usadas (barcode, busca de produto, vendas por período, movimentos de caixa/estoque, auditoria); adicionar/remover índices conforme evidência.
  **Testes/aceite:** nenhuma consulta quente faz seq scan em tabela grande; documento com antes/depois.
  **Commit:** `perf(db): revisa indices com base em plano de execucao`

- [ ] **1308 — Teste de carga do fluxo de venda**
  **Objetivo:** saber o limite antes do cliente descobrir. **Depende:** 910
  **Implementar:** script k6 (ou Gatling) simulando N caixas: login, abrir venda, 10 bipes, pagamento, conclusão; metas iniciais p95 < 300 ms por operação de item e < 1 s para conclusão.
  **Testes/aceite:** relatório com números; gargalos identificados e registrados.
  **Commit:** `test(perf): adiciona teste de carga do fluxo de venda`

- [ ] **1309 — Revisão de segurança e dependências**
  **Objetivo:** fechar arestas. **Depende:** 1305
  **Implementar:** `mvn dependency-check`/`npm audit`, revisão de permissões por endpoint, revisão de mensagens de erro (sem vazamento), conferência do checklist de §6, teste de sessão expirada e revogada; Renovate (ou Dependabot) configurado para abrir PR automático de atualização de dependências.
  **Testes/aceite:** sem CVE crítica/alta pendente; checklist assinado em `docs/seguranca.md`.
  **Commit:** `docs(security): revisa seguranca e dependencias`

- [ ] **1310 — Runbook e checklist de go-live**
  **Objetivo:** operar sem heroísmo. **Depende:** todos
  **Implementar:** `docs/runbook.md` (subir/derrubar, abrir/fechar caixa, o que fazer com queda de rede/banco, restore de backup, contatos) + checklist de produção (variáveis, secrets, usuário inicial, migrações, backup, monitoração, treinamento).
  **Testes/aceite:** uma pessoa que nunca viu o sistema consegue subir o ambiente seguindo o documento.
  **Commit:** `docs: adiciona runbook operacional e checklist de go-live`

---

## Fase 14 — Fiscal: NFC-e via provedor (≈ 3–4 semanas, pós-MVP)

> **Não bloqueia o MVP.** Iniciar somente com os pré-requisitos de §18.6 do plano (UF, regime,
> obrigatoriedade, certificado A1, CSC e NCM/CEST/CSOSN por produto) e com a impressão de cupom
> funcionando. Toda integração fica atrás da porta `FiscalGateway`; os testes usam um gateway fake e
> **nenhum teste automatizado depende da SEFAZ**.
> **Estimativa:** ~3–4 semanas de um desenvolvedor, incluindo homologação em uma UF.

- [ ] **1401 — Migration de configuração fiscal e fila**
  **Objetivo:** guardar dados do emitente e a fila de documentos. **Depende:** 004, 1306
  **Implementar:** `fiscal_settings` (CNPJ, IE, CRT/regime, CSC + idToken, série, ambiente homologação/produção, provedor, modo de contingência, credenciais por variável de ambiente) + `fiscal_documents` (outbox: `sale_id`, model, series, number, status `PENDING|PROCESSING|AUTHORIZED|REJECTED|CONTINGENCY|CANCELLED`, access_key, protocol, xml, rejection_code/message, attempts, next_retry_at) + `fiscal_inutilizations`; grants sem `DELETE` em `fiscal_documents`.
  **Testes/aceite:** migrations aplicam; status restrito por check; seed de homologação no perfil dev.
  **Commit:** `feat(fiscal): cria configuracao e fila de documentos fiscais`

- [ ] **1402 — Migration: campos fiscais de produto**
  **Objetivo:** produto apto a virar item de nota. **Depende:** 1401
  **Implementar:** migration aditiva em `products` (NCM, CEST, CFOP padrão, CSOSN/CST, cEAN quando diferente do barcode).
  **Testes/aceite:** colunas nulas não quebram nada existente; produtos sem NCM são identificáveis por consulta.
  **Commit:** `feat(fiscal): adiciona campos fiscais ao produto`

- [ ] **1403 — Domínio `FiscalDocument`**
  **Objetivo:** estado e validações puras. **Depende:** 1401
  **Implementar:** máquina de estados; validação de chave de acesso (44 dígitos + DV); justificativa de cancelamento (mínimo 15 caracteres); regras de retry/backoff.
  **Testes/aceite:** 10+ testes unitários de transição de estado, chave inválida e justificativa curta.
  **Commit:** `feat(fiscal): adiciona modelo de dominio do documento fiscal`

- [ ] **1404 — Porta `FiscalGateway` + gateway fake**
  **Objetivo:** integração isolada e testável. **Depende:** 1403
  **Implementar:** interface (emitir, consultar, cancelar, inutilizar) + DTOs + `FakeFiscalGateway` configurável para sucesso, rejeição e timeout.
  **Testes/aceite:** testes de contrato da porta rodando contra o fake.
  **Commit:** `feat(fiscal): define porta de integracao fiscal`

- [ ] **1405 — Repositório e claim da fila**
  **Objetivo:** nenhum documento processado duas vezes. **Depende:** 1401
  **Implementar:** `FiscalDocumentRepository` + claim com `SELECT ... FOR UPDATE SKIP LOCKED` (lote configurável) + devolução à fila por timeout de `PROCESSING`.
  **Testes/aceite:** dois workers concorrentes pegam lotes disjuntos; documento travado volta à fila após timeout.
  **Commit:** `feat(fiscal): adiciona fila com claim seguro`

- [ ] **1406 — Mapeamento venda → payload fiscal**
  **Objetivo:** transformar a venda em dados da nota. **Depende:** 1402, 1404
  **Implementar:** monta itens (NCM/CFOP/CSOSN, unidade, quantidade, valores), totais, pagamentos, CPF opcional e dados do emitente; valida pré-condições e marca `BLOCKED` com motivo quando falta NCM/CEST.
  **Testes/aceite:** venda de teste gera payload completo; produto sem NCM bloqueia com motivo legível.
  **Commit:** `feat(fiscal): mapeia venda para payload fiscal`

- [ ] **1407 — Emissão assíncrona (worker)**
  **Objetivo:** emitir sem travar o caixa. **Depende:** 1405, 1406
  **Implementar:** `@Scheduled` que consome a fila, chama o gateway, grava chave/protocolo/XML em caso de sucesso, trata rejeição e erro de rede com backoff exponencial e limite de tentativas; auditoria `FISCAL_DOCUMENT_AUTHORIZED` / `FISCAL_DOCUMENT_REJECTED`.
  **Testes/aceite:** sucesso, rejeição e timeout cobertos com o fake; nenhum documento perdido; auditoria em todos os casos.
  **Commit:** `feat(fiscal): emite documentos de forma assincrona`

- [ ] **1408 — Enfileirar na conclusão da venda**
  **Objetivo:** venda concluída gera documento pendente. **Depende:** 1407, 906
  **Implementar:** na mesma transação da conclusão (após os movimentos), inserir `fiscal_documents` `PENDING` com série/número alocados; **nunca** chamar a SEFAZ dentro da transação da venda.
  **Testes/aceite:** conclusão cria exatamente 1 documento pendente; falha ao criar o documento derruba a transação inteira (nada fica pela metade).
  **Commit:** `feat(fiscal): enfileira nota na conclusao da venda`

- [ ] **1409 — Consulta, retry e monitoramento (API)**
  **Objetivo:** enxergar e reprocessar a fila. **Depende:** 1408
  **Implementar:** `GET /fiscal-documents` (filtros status/período), `GET /fiscal-documents/{id}`, `POST /fiscal-documents/{id}/retry`, `GET /sales/{id}/fiscal-document`; permissões `fiscal.read` / `fiscal.write`.
  **Testes/aceite:** retry reprocessa rejeitado; permissões aplicadas; listagem paginada.
  **Commit:** `feat(fiscal): expoe consulta e reprocessamento de documentos`

- [ ] **1410 — Impressão do cupom fiscal (DANFE-NFC-e)**
  **Objetivo:** entregar o cupom ao cliente. **Depende:** 1407
  **Implementar:** porta `ReceiptPrinter` (ESC/POS de rede) com QR Code enviado como comando nativo + layout do DANFE simplificado; `GET /sales/{id}/receipt` devolve cupom fiscal autorizado ou cupom não fiscal quando pendente.
  **Testes/aceite:** layout validado em impressora real (ou emulador); QR Code lido por leitor 2D aponta para a URL correta.
  **Commit:** `feat(fiscal): imprime DANFE-NFC-e`

- [ ] **1411 — Cancelamento fiscal**
  **Objetivo:** cancelar dentro da janela legal. **Depende:** 1409, 1304
  **Implementar:** `POST /fiscal-documents/{id}/cancel` com justificativa (≥ 15 caracteres), validação da janela por UF (configurável), chamada ao gateway e auditoria `FISCAL_DOCUMENT_CANCELLED`; o estorno de venda consulta o estado fiscal antes de decidir entre cancelar e emitir devolução.
  **Testes/aceite:** cancelamento dentro da janela funciona; fora dela → 422 `CANCELLATION_WINDOW_EXPIRED`; estorno de venda com nota autorizada dispara o fluxo correto.
  **Commit:** `feat(fiscal): implementa cancelamento de nota`

- [ ] **1412 — Inutilização de numeração**
  **Objetivo:** não deixar buraco na sequência. **Depende:** 1407
  **Implementar:** inutilização automática quando um número alocado não gera documento autorizado + `POST /fiscal-inutilizations` manual; registro em `fiscal_inutilizations`.
  **Testes/aceite:** número órfão é inutilizado e auditado; falha na inutilização gera alerta e não é silenciada.
  **Commit:** `feat(fiscal): inutiliza numeracao orfa`

- [ ] **1413 — Contingência**
  **Objetivo:** a loja não para quando o provedor cai. **Depende:** 1407
  **Implementar:** `fiscal_settings.contingency_mode` (`QUEUE_AND_PRINT_NONFISCAL` | `BLOCK_SALE`); no modo fila, imprime cupom não fiscal com aviso de pendência e a fila drena quando o serviço volta.
  **Testes/aceite:** com gateway fora, a venda conclui e o cupom sai com aviso; quando o serviço volta, os documentos são transmitidos sem duplicidade.
  **Commit:** `feat(fiscal): trata contingencia do provedor`

- [ ] **1414 — Observabilidade fiscal**
  **Objetivo:** detectar problema antes do contador. **Depende:** 1407
  **Implementar:** métricas (`fiscal_documents_pending`, `fiscal_authorization_seconds`, `fiscal_rejections_total`), alerta para pendências acima de X minutos e logs com série/número/chave.
  **Testes/aceite:** métricas aparecem após o fluxo; alerta documentado no runbook.
  **Commit:** `feat(fiscal): adiciona metricas e alertas fiscais`

- [ ] **1415 — Homologação assistida**
  **Objetivo:** provar o fluxo no ambiente real de teste. **Depende:** 1410–1414
  **Implementar:** roteiro em `docs/fiscal-homologacao.md`: emitir, consultar, cancelar, inutilizar, simular rejeição e contingência; registrar evidências (chave, protocolo, XML).
  **Testes/aceite:** roteiro executado com evidências anexadas; nenhum erro aberto.
  **Commit:** `docs(fiscal): registra homologacao com evidencias`

- [ ] **1416 — Operação e treinamento**
  **Objetivo:** o operador sabe o que fazer quando dá errado. **Depende:** 1415
  **Implementar:** seção fiscal no runbook (significado de cada status, o que fazer com rejeição, quando chamar o contador, como cancelar) + treinamento curto do operador.
  **Testes/aceite:** operador explica o procedimento com o runbook em mãos.
  **Commit:** `docs(fiscal): adiciona runbook e treinamento fiscal`

- [ ] **1417 — Go-live fiscal com rollback**
  **Objetivo:** ligar em produção com saída de emergência. **Depende:** 1416
  **Implementar:** chave de ativação da emissão (`fiscal_settings.enabled`), série inicial, certificado A1 e CSC de produção; plano de rollback para cupom não fiscal se algo falhar no primeiro dia.
  **Testes/aceite:** ativação/desativação sem deploy; primeira nota de produção autorizada, com backup antes e depois.
  **Commit:** `feat(fiscal): habilita emissao fiscal em producao`

---

## Extras pós-MVP (não bloqueiam a entrega)

| Item | Por que depois | Passo sugerido |
| --- | --- | --- |
| Cadastro rápido de produto pela TUI (barcode novo) | melhora operação, mas não é essencial ao fluxo | após 1113 |
| Impressão de cupom (ESC/POS de rede) | exige hardware e porta `ReceiptPrinter` | após 1120 |
| PIN de operador para troca rápida de turno | reduz atrito; exige política própria de segurança | após 1118 |
| Paginação por cursor em vendas/auditoria | só quando volume justificar | após 1307 |
| Cache Caffeine de produto por barcode | só com latência medida | após 1308 |
| Arquivos `.http` com exemplos de request | acelera teste manual e onboarding, mas não bloqueia nada | após 410 |
| `openapi-diff` no CI (trava mudança incompatível no contrato) | só quando o contrato estabilizar | após 784 |
| Múltiplas lojas operacionais (UI + permissão por loja) | requer operação real multi-loja | fase própria |
| Fiscal (NFC-e/SAT) | requer módulo isolado e provedor; desenho em §18 do plano | **Fase 14** (≈ 3–4 semanas) |
| Modo offline na TUI | complexidade alta, ganho duvidoso | não recomendado no curto prazo |
| OpenTelemetry / native image / broker | sem consumidor ou dor concreta | reavaliar anualmente |

---

## Marcos de entrega

| Marco | Passos | Significado |
| --- | --- | --- |
| **M1 — Fundação** | 001–012 | projeto roda, migra, testa, integra |
| **M2 — Acesso** | 101–115, 201–214, 301–310 | login, sessão, RBAC e auditoria funcionando |
| **M3 — Catálogo e Caixa** | 401–414, 501–502, 601–614 | produtos, clientes e caixa operacionais |
| **M4 — Estoque** | 701–707 | saldo e ledger confiáveis |
| **M5 — Venda completa** | 801–814, 901–910 | venda com pagamento, estoque e caixa em uma transação |
| **M6 — Auditoria investigável** | 1001–1005 | qualquer operação reconstruível |
| **M7 — PDV em operação (MVP)** | 1101–1120 | TUI operando o fluxo completo |
| **M8 — Retaguarda** | 1201–1214 | administração pelo navegador |
| **M9 — Produção** | 1301–1310 | observabilidade, backup, proxy, carga e runbook |
| **M10 — Fiscal (NFC-e)** | 1401–1417 | emissão assíncrona via provedor, contingência, inutilização e homologação (≈ 3–4 semanas) |
