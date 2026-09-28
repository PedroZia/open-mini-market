# Como trabalhamos — agentes, subagentes e orquestração

> **Para que serve:** reproduzir em outra máquina o modo como as sessões deste repositório trabalham —
> quem decide, quem implementa, como os subagentes são encadeados, quais skills e MCP entram e onde o
> estado vive. As **regras** estão no [`../AGENTS.md`](../AGENTS.md); a **sequência**, no
> [`roadmap.md`](./roadmap.md); aqui está o **como operamos**.
>
> **Retrato em 2026-09-28:** 180 de 221 passos concluídos (restam as Fases 12–14: `1201`–`1417`);
> último commit `4ad1fdc chore(tui): aposenta a Ink e ativa a UI em OpenTUI`.

## 1. A hierarquia em uma tela

```text
dono
 │  prompt de missão  ("orquestre a fase X")  ou  "próximo passo"
 ▼
sessão principal — MESTRE (um contexto por missão/fase)
 │
 ├── delega ──▶ subagente `fase`  (orquestra UMA fase; não edita arquivos)
 │                   │
 │                   ├── delega ──▶ subagente `passo`    (1 passo: implementa, testa, commita)
 │                   └── consulta ─▶ subagente `explore` (reconhecimento, somente leitura)
 │
 └── ou executa a skill `passo` direto (passo único / fase curta, sem intermediário)
```

- **Nível de aninhamento:** `experimental.subagent_depth: 2` no [`.opencode/opencode.json`](../.opencode/opencode.json)
  é o que permite um subagente (`fase`) abrir outro subagente (`passo`). Sem essa chave, só a sessão
  principal delega; `fase` não conseguiria chamar `passo`.
- **Por que dividir assim:** o contexto do mestre não incha com código (ele só vê relatórios); cada
  passo roda em contexto novo e barato; e quem confere o resultado não é quem implementou.
- **Três níveis é o teto.** `passo` não delega — se travar, devolve `PERGUNTA:` para quem o chamou.

## 2. Os papéis

| Papel | Quem é | Edita? | Commita? | Reporta |
| --- | --- | --- | --- | --- |
| **Dono** | a pessoa na sessão principal | — | — | dá missão, aprova escopo, responde `PERGUNTA:` |
| **Mestre** | a própria sessão principal (agente `build`) | não implementa; pode rodar gate | não | ≤ 5 linhas por onda + checkpoint por fase |
| **`fase`** | subagente de [`.opencode/agents/fase.md`](../.opencode/agents/fase.md) | **não** (permissão de edição negada de propósito) | não | formato fixo: `FASE/ONDA/PLANO/POR PASSO/GATE DA ONDA/PERGUNTAS/RISCOS/PRÓXIMA ONDA` |
| **`passo`** | subagente de [`.opencode/agents/passo.md`](../.opencode/agents/passo.md) | sim | sim (1 passo = 1 commit) | `STATUS/COMMIT/TESTES/ARQUIVOS/DECISÕES/RISCOS` |
| **`explore`** | subagente nativo (somente leitura) | não | não | arquivos, telas e testes que o passo vai tocar |
| **`general`** | subagente nativo (curinga) | sim | conforme o prompt | fallback quando `fase`/`passo` falham |

Regras de convivência entre eles:

- `fase` **nunca pergunta ao dono**: devolve `PERGUNTA:` e para — quem media com o dono é o mestre.
- `passo` **nunca decide escopo**: dúvida de escopo/arquitetura = `PERGUNTA:` + opções + recomendação.
- Correção de um passo que falhou volta para a **mesma sessão** do subagente (passe o `sessionID`) —
  não abra um subagente novo para "adivinhar" o que o primeiro fez.

## 3. O ciclo de um passo (inegociável)

É o mesmo do `AGENTS.md` e do [`roadmap.md`](./roadmap.md), executado pelo `passo` (ou pelo mestre, em
passo único):

1. **Determinar o passo** — o primeiro `- [ ]` do roadmap na ordem do arquivo, ou o número indicado.
   Dependência marcada `- [ ]`? Para e avisa.
2. **Planejar antes de codar** — lista de arquivos e onde cada teste mora. Se o diff estimado passa de
   ~300 linhas, **divide em `NNa`/`NNb` e registra a divisão no roadmap antes de codar** (precedentes
   reais: `1125a/b/c`, `1126a/b/c/d`).
3. **Implementar** exatamente o escopo, respeitando as fronteiras (api → application → domain/portas;
   infrastructure implementa portas).
4. **Testar** no nível adequado: regra pura → unitário; persistência → integração com PostgreSQL real
   (Dev Services); endpoint → RestAssured. H2 e mock de banco são proibidos.
5. **Gate** — backend: `cd backend; .\mvnw.cmd verify`; TUI/Web: `npm test && npx tsc --noEmit`
   (Fase 11b: `bun test src/opentui` + `tsc --noEmit`). Vermelho se conserta; nunca se avança vermelho.
6. **Fechar no mesmo commit** — checkbox `- [ ]` → `- [x]` no roadmap **no mesmo commit** do código,
   mensagem `tipo(módulo): descrição` em pt-BR, citando o critério de aceite. Se a skill
   `ponytail-review` estiver disponível, roda no diff antes de commitar.
7. **Reportar** no formato do agente.

Conferência do mestre/`fase` depois de cada passo: `git log -1` (commit existe?), `git show --stat`
(escopo?), `git status --short` (árvore limpa?), checkbox marcado, mensagem no padrão, aceite citado e
gate verde no relatório. Falhou de novo? Reverte ao último estado verde e reporta — **nunca** segue com
build vermelho.

## 4. Ondas, fases e checkpoints

- **Onda:** 3–5 passos por invocação do `fase` (ou menos no começo de uma fase para calibrar). Os passos
  de uma onda são sequenciais; `explore` pode rodar 2–3 em paralelo, e escrita em paralelo só com
  **worktree** separado (precedente: o spike `1121` rodou no worktree `open-mini-market-spike-1121`,
  branch `spike/1121-opentui`, sem tocar o `main` até o go).
- **Fim de onda:** o mestre confere os commits/checkboxes e reporta ao dono. Continua a fase passando o
  mesmo `sessionID` para o `fase` com a próxima onda.
- **Fim de fase:** o mestre roda o gate por fora e entrega o checkpoint — feito, testes, critérios de
  aceite, riscos/dúvidas e próximo passo — **e só então** emenda a fase seguinte (regra 6 do `AGENTS.md`).
- **Critérios de parada** (o agente para e devolve `PERGUNTA:`): conflito plano × roadmap, decisão de
  escopo/arquitetura, dependência não marcada, bloqueio que o implementador não resolveu, passo que
  estoura ~300 linhas sem divisão registrada, gate vermelho não resolvido em 2 tentativas.

## 5. As formas de orquestrar (com os prompts reais)

### 5.1 Mestre → `fase` (fases longas)

O mestre invoca a ferramenta de subagente com `agent="fase"` e um prompt **curto** — as regras já estão
no system prompt do `fase`:

```text
FASE: 7 | ONDA: 701–705 | Decisões do mestre: [bloco do ledger que importa] |
Skills: migration, endpoint | MCP: postgres (banco de dev) |
Você não edita, não commita, não faz push: delegue ao `passo` e reporte no seu formato.
```

Comportamento: `fase` lê a fase no roadmap, confere dependências, delega um passo por vez ao `passo`,
confere commit/checkbox/gate de cada um e devolve o relatório; o mestre confere por fora (`git log`,
`git status`) e segue. **Fallback:** se `fase` falhar, o mestre chama `general` um passo por vez usando
o `.opencode/agents/passo.md` como base do prompt.

### 5.2 Prompt de handoff (contexto limpo) — o caso da Fase 11b

Quando a sessão do mestre enche (ou o dono quer trocar de máquina/modelo), o padrão é pedir ao mestre:

> "gere o prompt para outro agente com contexto limpo continuar por favor"

O mestre escreve um **briefing autossuficiente** que vira a primeira mensagem de uma sessão nova. Foi
exatamente assim que a Fase 11b rodou: a sessão "Orquestração do backend PDV: Fases 3–10" (que executou
as fases 3–10 e o hardening `1006`–`1012`) gerou o prompt do **Anexo B**, e uma sessão nova — com
contexto zerado — registrou a Fase 11b no roadmap e a executou de `1121` a `1130`, incluindo o spike
go/no-go em worktree próprio e o cut-over.

O que um bom handoff **precisa** ter:

1. **Estado atual verificável** — onde o roadmap parou, migrations aplicadas/próxima livre, comandos de
   conferência, decisões já tomadas (o ledger, §6).
2. **Missão e escopo** — o que fazer e, principalmente, o que **não** fazer.
3. **Gate de cada tipo de passo** e o critério de pronto (commit + checkbox + aceite).
4. **Skills e MCP** que ajudam em cada passo.
5. **Critérios de parada** e autorizações dadas pelo dono (ex.: "pode ir até o fim da fase X",
   "push ao fim de cada fase").
6. **Formato do relatório** esperado.

### 5.3 Direto na sessão principal (passo único)

Para um passo só (ou para "próximo passo"), o mestre não precisa de intermediário: ele carrega a skill
[`passo`](../.opencode/skills/passo/SKILL.md) e executa o ciclo da §3 na própria sessão. Use `fase`
quando a fase tem muitos passos (economiza o contexto do mestre); vá direto quando é um passo ou a fase
está no começo/instável.

## 6. O que viaja no Git × o que é da máquina

**Viaja no Git (é o que basta para outro PC):**

- `AGENTS.md`, `docs/roadmap.md` (checkboxes = estado real), `docs/plano-tecnico.md`, `docs/referencias.md`
  e este documento;
- `.opencode/agents/` (`fase`, `passo`) e `.opencode/skills/` (6 skills do projeto);
- `.opencode/opencode.json` (`subagent_depth: 2`);
- planos aprovados na raiz (ex.: `melhorias_terminal.md`, que virou a Fase 11b).

**É local da máquina (precisa ser recriado no outro PC):**

- **Sessões do OpenCode** e `sessionID` (`%USERPROFILE%\.local\share\opencode\...`): não se retoma uma
  sessão de outra máquina. O que substitui isso é o roadmap + o ledger + um novo prompt de handoff.
- **Skills globais** em `%USERPROFILE%\.agents\skills\` (§7.2) — copie a pasta.
- **Config global** do OpenCode (`%USERPROFILE%\.config\opencode\opencode.jsonc`): MCP, permissões,
  providers/auth (não versionar chaves — use `opencode auth login`).
- **Ledger de decisões** em `%USERPROFILE%\.opencode\plan\pdv-backend-ledger.md` (fora do repo, de
  propósito): decisões e armadilhas que sobrevivem a compaction/restart. Atualizado ao fim de cada fase.
  Se não existir no outro PC, o mestre o reconstrói a partir do roadmap e dos commits.
- Ferramentas: JDK 25, Docker, Node 22+, Bun 1.3+ (TUI), banco/containers/volumes.

**Regra de ouro:** o estado do projeto vive no **roadmap e nos commits**, não na cabeça de um agente.
Qualquer sessão nova, em qualquer PC, recomeça lendo `AGENTS.md` + roadmap + ledger.

## 7. Skills e MCP

### 7.1 Skills do projeto (versionadas em `.opencode/skills/`)

| Quando | Skill |
| --- | --- |
| qualquer passo | `passo` |
| migration, tabela, coluna, índice, seed | `migration` |
| endpoint REST | `endpoint` |
| concorrência, lock, race | `teste-concorrencia` |
| Fase 11 (TUI) | `tui-tela` |
| Fase 12 (retaguarda React) | `web-feature` (+ `ui-ux-pro-max` para decisão visual) |

### 7.2 Skills globais da máquina (não versionadas)

Nesta máquina elas estão em `%USERPROFILE%\.agents\skills\` e são lidas pelo OpenCode. Para outro PC,
copie a pasta inteira (são arquivos markdown):

| Skill | Para quê |
| --- | --- |
| `ponytail`, `ponytail-review`, `ponytail-audit`, `ponytail-debt`, `ponytail-gain`, `ponytail-help` | solução mínima / revisão de over-engineering (o `passo` roda `ponytail-review` no diff antes de commitar) |
| `agent-browser` | automação de navegador / QA exploratório |
| `opentui` | docs locais da OpenTUI (Fase 11b) |
| `ui-ux-pro-max` | decisões visuais da retaguarda (Fase 12) |
| `find-skills` | descobrir/instalar skills |

Se alguma faltar, o fluxo **degrada sem quebrar**: as referências a elas são condicionais ("se
disponível"). O que o repo exige são as skills do projeto (§7.1).

### 7.3 MCP servers

| MCP | Papel |
| --- | --- |
| `postgres` | leitura no banco de **dev** (`localhost:5432/minimarket`), read-only: grants, índices, seeds, ledger, auditoria. **Não** enxerga o banco de teste (Dev Services, efêmero) |
| `playwright` / `browser` | E2E e preview de telas da Fase 12 |
| `agent-browser` | automação extra de navegador (opcional) |

Snippet do config global (`%USERPROFILE%\.config\opencode\opencode.jsonc`) para o outro PC:

```jsonc
"mcp": {
  "postgres": {
    "type": "local",
    "enabled": true,
    "command": ["npx", "-y", "@modelcontextprotocol/server-postgres",
                "postgresql://minimarket:minimarket@localhost:5432/minimarket"]
  },
  "playwright": { "type": "local", "command": ["npx", "@playwright/mcp"] },
  "agent-browser": { "type": "local", "command": ["agent-browser", "mcp", "--tools", "all"] }
}
```

Confira com `opencode mcp list`: `postgres` (e `playwright`) precisam aparecer `connected`.

**Nunca use `/commit` nem `create_merge_request`**: o plugin `commit-flow` da máquina é de GitLab e este
repositório é GitHub — commit e push são `git` puro.

## 8. Montando em outro PC (checklist)

1. **Pré-requisitos:** Git, JDK 25, Docker (ligado — os testes de integração sobem PostgreSQL real via
   Dev Services), Node 22+, Bun 1.3+ (só para a TUI/OpenTUI) e PowerShell no Windows.
2. **OpenCode v2:** instalar (`npm i -g opencode-ai`, como nesta máquina; depois `opencode --version`),
   autenticar os providers (`opencode auth login`) e conferir `opencode mcp list`.
3. **Clonar o repo** — o `.opencode/` vem junto (agentes `fase`/`passo`, 6 skills, `subagent_depth: 2`).
   Atenção ao `.gitattributes` (LF forçado no índice/worktree; não mexa no `core.autocrlf`).
4. **Copiar as skills globais** de `%USERPROFILE%\.agents\skills\` (§7.2), se quiser o fluxo completo.
5. **Criar o config global** com os MCPs (§7.3) — banco, Playwright e, se usar, agent-browser.
6. **Subir o ambiente de dev** conforme o [`README.md`](../README.md): `docker compose up -d postgres`;
   `cd backend; .\mvnw.cmd quarkus:dev "-Dquarkus.http.port=8081"`; TUI em `terminal/`.
7. **GitHub:** credenciais para push (HTTPS ou `gh auth login`); nunca commitar `.env`/segredo — o
   config global desta máquina já nega leitura de `.env`.
8. **Primeira sessão:** abrir o OpenCode no repo e escolher o caminho:
   - missão de fase inteira → colar o prompt de handoff (**Anexo A** ou **B**, adaptando o caminho do
     repo) ou pedir ao mestre para gerar um novo;
   - passo único → dizer "próximo passo" ou "faça o passo `1204`".
9. **Validar:** `git log -1` e `git status --short` limpos; checkbox do roadmap marcado no commit; gate
   verde no relatório do subagente.

## 9. Receitas de prompt (dia a dia)

| Você diz | O que acontece |
| --- | --- |
| "próximo passo" / "faça o passo 1204" | mestre executa a skill `passo` (ou delega um `passo`), ciclo completo + commit |
| "orquestre o restante da Fase 12 em ondas de 3" | mestre invoca `fase`; a onda volta com relatório e o mestre confere |
| "pode continuar" | destrava uma sessão parada entre ondas |
| "gere o prompt para outro agente com contexto limpo continuar" | mestre escreve o handoff (§5.2) para outra sessão/PC |
| "feche as pendências" | mestre varre o que ficou aberto (docs, pendências registradas) e conclui |
| "revise o último commit" / "corrija o passo 1126b" | revisão/correção na mesma sessão do subagente (`sessionID`) |
| "divida o passo X em subpassos" | registra a divisão no roadmap **antes** de codar |
| (o agente responde `PERGUNTA:`) | decisão de escopo/arquitetura é do dono: responder e mandar seguir |

## 10. Histórico real (de onde vem este documento)

- **23–24/09** — sessões "Orquestração automática de agentes..." e "Orquestração do backend PDV:
  Fases 3–10": o prompt do **Anexo A** levou o backend de `307` a `1005`, com hardening `1006`–`1012`,
  ledger fora do repo e push por fase para a CI validar.
- **25/09** — `melhorias_terminal.md` (plano do dono) decidiu migrar a TUI para OpenTUI React; a sessão
  do mestre gerou o handoff do **Anexo B**; a sessão nova registrou a "Fase 11b — OpenTUI React" no
  roadmap e a executou: spike `1121` (go/no-go com evidência em worktree/branch próprio), `1122` no
  `main` (backend independente da UI), telas `1123`–`1129` em ondas, cut-over `1130`.
- **Commits que mostram o fluxo:** `1cbc279 docs(tui): divide o autoteste do leitor em dois passos` →
  `eda20dd`/`3cae330 feat(tui): ...` (subpassos da divisão); `1947dd2 docs(tui): divide o cut-over em
  dois passos` → `258fe8b test(tui): reescreve o E2E` → `4ad1fdc chore(tui): aposenta a Ink`.
- **Hoje** — 180/221 passos; próximo trabalho: Fase 12 (`1201`+), com `web-feature` e Playwright.

## 11. O que não fazer

- Não abrir dois escritores na mesma árvore de trabalho (paralelismo só com worktree e escopos
  disjuntos — conflito de índice do Git).
- Não aceitar commit fora do escopo do passo, nem checkbox sem gate verde, nem "depois eu arrumo".
- Não editar migration já aplicada, não silenciar auditoria, não commitar segredo/`.env`/dump.
- Não deixar `fase`/`passo` decidirem escopo: `PERGUNTA:` sobe para o dono.
- Não usar `/commit`/`create_merge_request` (GitLab) nem `--amend`, `reset` destrutivo ou branch na
  sessão do mestre.
- Não perder o ledger: ele é o que faz a retomada em outro PC ser barata.

---

## Anexo A — prompt do mestre (Fases 3–10, real)

> Prompt colado na sessão principal em 24/09/2026 para executar as Fases 3 a 10 do backend. Caminhos
> sanitizados (`C:\caminho\do\repo` = clone local); o restante é fiel ao original.

```text
Você é o ORQUESTRADOR MESTRE do roadmap do PDV minimercado.
Repo: C:\caminho\do\repo (Windows/PowerShell). Docker está rodando.
Leia AGENTS.md antes de tudo: ele é a lei. Este prompt é o seu briefing; o roadmap é a sequência.

## MISSÃO
Levar o backend do passo **307** até o fim da **Fase 10 (1005)**, usando a hierarquia de orquestração
já configurada no repo (mestre → fase → passo). Você NÃO implementa passos: você decide, delega,
confere e reporta.

## ESTADO ATUAL (confira antes de começar)
- Fases 0–2 ✅. Fase 3 em andamento: **301–306 ✅**, faltam **307, 308, 309, 310**.
- Fases 4 a 10 pendentes (Fase 11+ é front, fora do seu escopo).
- Migrations aplicadas: V1 stores, V2 users, V3 rbac, V4 users_must_change_password, V5 auth_sessions,
  V6 audit_events. **Próxima livre: V7.**
- Comandos: `git log --oneline -5`, `git status --short`, primeiro `- [ ]` em docs/roadmap.md,
  gate `cd backend; .\mvnw.cmd verify`.

## COMO ORQUESTRAR
1. Invoque a ferramenta de subagente com `agent="fase"` (a lista de subagentes do seu system prompt
   pode estar desatualizada — os agentes do repo valem). Prompt do fase, curto:
   `FASE: N | ONDA: passos X–Y | Decisões do mestre: [bloco LEDGER relevante] | Skills: ... |
    MCP: ... | Não edite/não commite/não faça push (delegue). Reporte no seu formato.`
2. **Ondas de 3–5 passos.** Ao voltar: confira `git log --oneline`, `git status --short`, os
   checkboxes e o gate da onda. Reporte ao usuário em ≤5 linhas.
3. Continue a fase passando o `sessionID` retornado (mesma sessão de fase) com a próxima onda.
4. **Fim de fase:** rode o gate por fora, entregue o checkpoint (feito / testes / aceites / decisões /
   riscos / próximo) e siga para a próxima fase.
5. Fallback se `fase` falhar: invoque `general` um passo por vez, usando `.opencode/agents/passo.md`
   como base do prompt.

## CADÊNCIA
- Você foi autorizado a ir até o fim da Fase 10: checkpoint de fase é **relatório e segue**, não parada.
- Pare e pergunte SÓ quando: `PERGUNTA:` do fase, conflito plano × roadmap, bloqueio que ninguém
  resolveu, ou decisão de escopo/arquitetura. Traga opções + sua recomendação.
- **Push:** pergunte UMA vez, no início, se pode publicar ao fim de cada fase para a CI validar.
  Sem autorização, não faça push. Nunca use `/commit` nem `create_merge_request` (o plugin é GitLab;
  este repo é GitHub). Sem branch, sem `--amend`, sem `reset` destrutivo.
- Mantenha um ledger curto em `%USERPROFILE%\.opencode\plan\pdv-backend-ledger.md` (fora do repo),
  atualizado ao fim de cada fase, para sobreviver a compaction/retomada.

## LEDGER DE DECISÕES (injete nos briefs; não redescubra)

### Convenções (AGENTS.md)
- Código e identificadores em **inglês**; docs, commits e `@DisplayName` em **pt-BR**.
- Java 25, Quarkus 3.33 LTS, sem Lombok; Hibernate ORM/JPA explícito (**sem Panache**); schema só por
  Flyway; proibido H2 e mock de banco; dinheiro `numeric(14,2)` + BigDecimal HALF_UP; quantidade
  `numeric(14,3)`; datas `timestamptz` UTC.
- Um passo = um commit (checkbox no mesmo commit). Nunca editar migration aplicada. Não antecipar
  passos. Sem `BaseService`/`GenericRepository`. Nunca entidade JPA em DTO/JSON. Falha de auditoria
  derruba a transação (não silenciar).
- Passo que passar de ~300 linhas: **dividir em `NNa/NNb` e registrar no roadmap antes de codar**
  (precedente: 204a/204b).

### Migrations — números do roadmap estão +1 desatualizados
Use o próximo livre e **corrija o texto do passo no mesmo commit**:
categories=**V7**, products=**V8**, products_search_index=**V9**, customers=**V10**,
cash_registers=**V11**, cash_sessions=**V12**, cash_movements=**V13**, stock=**V14**,
document_sequences=**V15**, sales=**V16**, sale_items=**V17**, idempotency_keys=**V18**,
payments=**V19**.
`audit_events` é `bigint generated always as identity`; a role `minimarket_app` só tem SELECT/INSERT.

### Padrões de código
- `api → application → domain/portas`; `infrastructure` implementa portas; portas devolvem **records
  de aplicação**, nunca entidades.
- `@Transactional` no caso de uso (1 caso de uso = 1 transação), nunca no repositório.
- `Clock` injetado; nunca `Instant.now()` direto em regra.
- Config em `application.properties` com prefixo `minimarket.*`.
- IDs: UUIDv7 na aplicação (`UuidCreator.getTimeOrderedEpoch()`; `uuid-creator` já no pom).
- Paginação: envelope `PageResponse` (`items/page/size/totalItems/totalPages`), page default 0,
  size default 20 com **clamp** em 100, `sort=campo,asc|desc` com whitelist (nunca interpolar entrada
  do usuário em JPQL), `from` inclusivo / `to` exclusivo.
- Erros: `ErrorCode` (status + title) + `ProblemDetail` (`problem+json`) com `code` estável. Já
  existem: VALIDATION_ERROR, NOT_FOUND, METHOD_NOT_ALLOWED, CONFLICT, BUSINESS_ERROR, INTERNAL_ERROR,
  USERNAME_ALREADY_EXISTS, USER_NOT_FOUND, UNKNOWN_ROLE, UNKNOWN_PERMISSION, ROLE_NOT_FOUND,
  INVALID_CREDENTIALS, ACCOUNT_LOCKED, RATE_LIMITED, INVALID_CURRENT_PASSWORD, SESSION_EXPIRED,
  SESSION_IDLE_TIMEOUT, ACCESS_DENIED.

### Auth, autorização e auditoria (Fase 3)
- Bearer + identidade com roles e atributos `permissions` e `sessionId`. `AuthorizationService` (305)
  e `@RequirePermission` (306) prontos — o 307 aplica nas rotas e o 308 fecha o teste global.
- **Achado crítico do 207:** `@Authenticated`/`@RequirePermission` sozinhos devolvem **401 sem corpo**;
  o `problem+json` vem do challenge do mecanismo (206), que roda quando há **política por rota**
  (`quarkus.http.auth.permission.*`). No 307/308 prefira a **política global** `/api/v1/*` =
  `authenticated` + `permit` para as exceções públicas (`/api/v1/auth/login`, `/api/v1/meta`,
  `/q/health*`) e confirme com o teste do 308.
- **Atenção no 307:** os testes de API das Fases 1–2 chamam endpoints sem token; eles vão passar a
  receber 401 e precisam ser atualizados para autenticar (é trabalho esperado do 307).
- Login: header `X-Client: TUI|WEB` (default WEB); lock 5 falhas/15 min; rate limit 20/5 min por IP em
  memória (sem X-Forwarded-For); senha mínima 8; Argon2id via `password4j` (plano §6.2 atualizado).
- Sessões: expiração absoluta 12 h, idle 30 min WEB / 8 h TUI, touch de `last_seen_at` 1×/min;
  revogação em disable/reset/troca de senha.
- Auditoria: `AuditRecorder` grava na **mesma transação** do caso de uso; `OperationContext` (302)
  carrega ator/sessão/caixa/loja/requestId/IP. `audit_events` é append-only.

### Armadilhas já pagas (não repita)
- PostgreSQL **arredonda** timestamptz para µs: comparação de tempo em teste precisa de folga de 1 µs
  (ou use `Clock` fake em teste unitário).
- Testes de API **commitam de verdade**: sempre cleanup no `@AfterEach` (FKs `on delete restrict`).
- `user_roles` tem colunas extras; `@ManyToMany` só funciona porque `granted_at` tem default.
- Hibernate 7.2 tem `inet` nativo: `InetAddress` + `@JdbcTypeCode(SqlTypes.INET)`.
- Ciclo de módulos: `auth → users` já existe; comunicação inversa só por **evento CDI** (ArchUnit
  reprova ciclo) — precedente: revogação de sessões (213).
- Concorrência: `ExecutorService` + latch, sem `sleep` arbitrário.
- Porta 8080 do host está ocupada por outro projeto do usuário (IntelliJ): para subir o compose use
  `APP_PORT=8081`; os testes usam porta aleatória.
- Pendências conhecidas sem dono: `?active=abc` devolve 404 (deveria 400); lock do último ADMIN sem
  concorrência; roadmap cita proxy "1402" mas é o 1305.

### MCP e skills
- `postgres.query` é read-only e aponta para o banco de **dev** (localhost:5432/minimarket): use para
  conferir grants, índices, ledger, auditoria e para validar E2E manual. O banco de teste é efêmero.
- Skills por passo: `passo` sempre; `migration` nos de schema; `endpoint` nos de API;
  `teste-concorrencia` nos de lock/race (**604, 613, 703, 707, 804, 814, 908**).
- Fases 11/12 (browser/playwright) estão fora do seu escopo.

## ESCOPO DAS FASES 4–10
- **Fase 4 — Catálogo (401–414, 14):** categorias, produtos, barcode (caminho quente, 409/404
  específicos), preço auditado com motivo, `If-Match`/version, desativar/reativar, trigram se o
  `EXPLAIN` justificar.
- **Fase 5 — Clientes (501–502, 2):** tabela + CRUD/busca por nome/CPF/telefone, validação de CPF com
  dígitos verificadores.
- **Fase 6 — Caixa (601–614, 14):** registros, sessões e movimentos, índice único parcial de sessão
  aberta, domínio puro, abertura/sangria/suprimento/fechamento, concorrência, autorização/auditoria.
- **Fase 7 — Estoque (701–707, 7):** saldo + ledger com `balance_after`, `StockService.applyMovement`
  como **única porta**, lock pessimista e ordenação por `product_id` (evita deadlock), consulta,
  ajuste, entrada, concorrência.
- **Fase 8 — Vendas (801–814, 14):** numeração sequencial, domínio `Sale` com snapshot de preço,
  persistência, idempotência (`Idempotency-Key`), itens, desconto com permissão/motivo, cliente,
  consulta, cancelamento, concorrência/auditoria.
- **Fase 9 — Pagamentos e conclusão (901–910, 10):** tabela/domínio/persistência de pagamento,
  `AddPayment`, API, **`CompleteSale`** (núcleo: estoque + caixa + auditoria na mesma transação,
  ordenado por `product_id`), complete idempotente, concorrência, fechamento com vendas, fluxo
  completo ponta a ponta.
- **Fase 10 — Auditoria (1001–1005, 5):** consulta com filtros + paginação, histórico por entidade,
  índices validados com `EXPLAIN` em 100 k eventos, limpeza agendada de `idempotency_keys`,
  `docs/auditoria.md` com o catálogo de eventos.

## CRITÉRIO DE SUCESSO
- 307 até 1005 com checkbox marcado, um commit por passo no padrão, gate verde em toda onda e em toda
  fase, e CI verde por fase (se o push estiver autorizado).
- Ao final: relatório consolidado do backend (Fases 3–10) — o que existe, como rodar, decisões,
  riscos e o que ficou pendente para as Fases 11–14.
```

## Anexo B — prompt de handoff da Fase 11b (real)

> Gerado pelo mestre da sessão anterior a pedido do dono ("gere o prompt para outro agente com contexto
> limpo continuar") e colado como primeira mensagem da sessão "Orquestração da Fase 11b — OpenTUI
> React" em 25/09/2026. É o modelo de handoff descrito na §5.2.

```text
Você é o ORQUESTRADOR da Fase 11b — OpenTUI React — do PDV deste repositório. Você não implementa:
planeja, delega a subagentes, confere o resultado e reporta. Você orquestra os subagentes `passo`
(implementação de um passo) e `explore` (reconhecimento, somente leitura).

LEITURA OBRIGATÓRIA, nesta ordem, antes de agir:
1. AGENTS.md — regras inegociáveis (um passo por vez, ciclo implementar→testar→commit→checkbox,
   teto de ~300 linhas, commits pt-BR sem acento, Definition of Done).
2. melhorias_terminal.md (raiz do repo, commit 528c46a) — o plano aprovado: diagnóstico (§2),
   o que fica/muda (§3), ganhos (§4), riscos e GATE do spike (§5), polimento adiado (§6),
   passos 1121–1130 (§7), decisões pendentes (§9) e Anexo A (plano B se o spike der no-go).
3. docs/roadmap.md — formato dos passos e onde a Fase 11b entra (depois do último passo da
   Fase 11, antes da Fase 12).
4. docs/plano-tecnico.md §11.2/§11.3 e docs/leitores.md — UX da TUI e regras do leitor.
5. A skill `opentui` (docs locais) antes de qualquer passo da UI nova; a skill `passo` para o
   procedimento de execução; `endpoint`/`migration` no 1122; `teste-concorrencia` se surgir.

MISSÃO: executar a Fase 11b inteira, em ordem, um commit por passo e checkbox marcado, até o
cut-over (1130) — ou até um critério de parada.

PRÉ-REQUISITO: a Fase 11b ainda NÃO está no docs/roadmap.md. Registre a seção
"## Fase 11b — OpenTUI React" com os passos 1121–1130 no formato dos demais (Objetivo, Depende,
Implementar, Testes/aceite, Commit), fiéis à §7 de melhorias_terminal.md, incluindo o gate go/no-go
no 1121 e o cut-over no 1130. Commit: docs(roadmap): registra a fase 11b de migracao para OpenTUI.

COMO DELEGAR:
- Um `passo` por vez, sessão nova, com: o número, o TEXTO do passo (cole o bloco do roadmap), a
  fonte no plano, a(s) skill(s) a carregar, os arquivos que o recon apontou e o que conta como
  pronto (gate + commit + checkbox). Exija o ciclo do AGENTS.md.
- Use `explore` para reconhecimento dos arquivos/telas/testes do passo seguinte — pode rodar 2–3
  em paralelo (é leitura). Escrita em paralelo só em escopos de arquivo disjuntos; na dúvida,
  serialize. Nunca dois escritores na mesma árvore (conflito de índice do git). Para adiantar o
  1122 (backend puro) em paralelo, use um worktree separado e faça o merge você mesmo, só com
  ./mvnw.cmd verify verde.
- Depois de cada passo, confira: git log -1, git show --stat, checkbox marcado, aceite citado no
  commit, gate verde no relatório do subagente. Falhou? Mande corrigir na mesma sessão (sessionID)
  ou reverta ao último verde e reporte.

REGRAS PARA TODOS OS PASSOS:
- Backend: Java 25 sem Lombok; schema só por Flyway (migration aplicada nunca é editada);
  auditoria na mesma transação; UUIDv7 na aplicação; numeric(14,2)/numeric(14,3); timestamptz UTC;
  RestAssured com PostgreSQL real (Docker ligado); ./mvnw.cmd verify verde.
- TUI: core/ permanece puro (sem React/OpenTUI/rede); BR-12 (a TUI não calcula nada) e BR-14
  (código de barras bruto); pt-BR na tela; alvo mínimo 80×24 com fallback monocromático.
- UI nova: gate = tsc --noEmit + o runner decidido no spike (bun test ou vitest) verdes; testes de
  tela com o test renderer da OpenTUI; adicione as fontes do OpenTUI em docs/referencias.md no
  1123 (regra 4 do AGENTS.md).
- A Ink não recebe recurso novo durante a fase: só correção crítica. Ela é apagada no 1130.
- Nunca commite: segredo, .env, dump, migration aplicada, mudança no formato de audit_events.

ORDEM E ATENÇÃO:
1) 1121 — Spike OpenTUI é o GATE. Não vai para o main: branch/worktree e app mínimo. Prove os 6
   critérios da §5 (Bun 1.3+ no Windows do caixa OU Node 26.4 + --experimental-ffi; frames 80×24 e
   120×40; F1–F12; rajada do leitor interceptada ANTES do input focado; testRender sob o runner
   escolhido; venda de 50 itens fluida; rollback). Relatório curto com evidência. Se QUALQUER
   critério falhar ou ficar ambíguo: PARE e reporte (o Anexo A é o plano B; só o dono decide).
   Se tudo passar, considere go e siga.
2) 1122 — cadastro rápido backend (POST /api/v1/products/quick, permissão product.quick_create,
   auditoria, OpenAPI + schema.d.ts). Independente da UI; pode rodar em paralelo ao spike.
3) 1123 — fundação: src/opentui/ (renderer/root, tema, adaptadores KeyEvent→core/keys e →scanner,
   foco, lifecycle com renderer.destroy em toda saída), script start:opentui (Bun), referências.
4) 1124 a 1129 — ondas: entrada (login com senha mascarada própria, abertura, erro) → venda
   (scrollbox + leitura manual + relógio) → modais → pagamento/sucesso/fechamento → cadastro
   rápido na TUI → F11 2.0 (opcional). Porte os testes equivalentes da Ink a cada onda.
5) 1130 — cut-over: npm start/pdv.cmd → OpenTUI sob Bun; E2E dirigido pela UI nova e verde contra
   o backend de dev; apagar src/ui Ink, dependências Ink e useRawShortcuts; atualizar README,
   docs/leitores.md, docs/referencias.md e engines.

CRITÉRIOS DE PARADA (pare e reporte com evidência):
- Gate do spike falhou ou é ambíguo.
- Gate vermelho não resolvido em 2 tentativas (reverta ao último verde).
- Dúvida de escopo/arquitetura que o plano não responde — não decida sozinho; proponha a
  alternativa mais simples.
- Passo que estoura ~300 linhas sem subdivisão (1126a, 1126b...) registrada no roadmap antes.
- Decisão do dono pendente (Bun no caixa; runner dos testes; permissão do cadastro rápido;
  cut-over; leitura manual automática).

RELATÓRIO (a cada parada e no fim): passos concluídos (id, hash + mensagem do commit, checkbox,
evidência do aceite), testes rodados e resultado, pendências/deferidos, riscos e próximo passo.
Comece registrando a Fase 11b no roadmap e rodando o 1121. Não peça confirmação a cada passo; só
pare nos critérios acima.
```

## Anexo C — configs citadas

`.opencode/opencode.json` (versionado — é o que habilita subagente chamando subagente):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "experimental": {
    "subagent_depth": 2
  }
}
```

Config global (não versionado; cada PC tem o seu): MCP da §7.3 + providers/auth via `opencode auth`.
