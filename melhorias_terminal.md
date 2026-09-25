# Melhorias do terminal (PDV) — plano revisado: OpenTUI React

**Status:** proposta para revisão do dono. Nada aqui foi executado nem registrado em `docs/roadmap.md`.
**Revisão 2 (2026-09-25):** o dono decidiu migrar a UI do PDV para **OpenTUI React** (a intenção
original, que o passo 1101 resolveu com Ink). O polimento visual da proposta anterior fica como
**backlog adiado** (§6) e será feito na UI nova, depois do cut-over. Ink continua rodando em produção
durante a transição e é **apagada no fim**.

**Como isto vira trabalho:** cada passo aprovado entra em `docs/roadmap.md` (regra 1 do `AGENTS.md`)
como continuação da Fase 11 — **"Fase 11b — OpenTUI React"**, passos `1121`+ — e roda o ciclo de
sempre: implementar → testes (`bun test`/`npm test` conforme o passo, + `tsc --noEmit`) → commit →
checkbox. O teto de ~300 linhas por passo continua valendo; por isso as telas foram agrupadas em
ondas, não em um passo único.

---

## 1. Resumo executivo

| Decisão | Consequência |
| --- | --- |
| Migrar a UI para OpenTUI React | `core/` (máquina de estados, scanner, atalhos, dinheiro) e `api/` **não mudam**; `ui/` Ink (~4,2 mil linhas + 6,4 mil de teste) é portado em ondas e apagado no cut-over |
| Runtime passa a ser Bun (ou Node 26.4+ com `--experimental-ffi`) | a máquina do caixa ganha o Bun; `pdv.cmd`/`npm start` mudam no cut-over (1130) |
| Spike antes de investir | o passo 1121 prova engine, leitor, F-keys, testes e performance **na máquina real do caixa**; se der no-go, vale o plano B do Anexo A (polimento na Ink) |
| Polimento visual adiado | tema, feedback efêmero, totais em destaque, estados vazios e afins viram requisitos da UI nova (§6) e são feitos **depois** do cut-over, pelo dono |

O que a migração entrega de imediato, sem "polimento" nenhum: `<input>` para a **leitura manual**
(F-01), `<scrollbox>` para a **janela rolante** da lista (F-04), alternate screen com restauração
**nativa** do terminal (V-07), F1–F12 entregues de verdade pelo teclado, layout reativo com
`useTerminalDimensions` e animação com `useTimeline`.

---

## 2. Diagnóstico (por que migrar) — o que dói hoje

### 2.1 Leitura manual do código: inexistente

- `terminal/src/ui/SaleScreen.tsx` (linhas 162–196) manda **todo** caractere imprimível para
  `createScanner()`; com intervalo ≥ 50 ms o `core/scanner.ts` descarta o buffer e **a tela não
  mostra nada**. O plano técnico §11.3 previa "input de bipe sempre visível", nunca implementado.
- Nenhum modal serve de plano B: F2/F5/F6 são de outra finalidade e fecham a venda.

### 2.2 Cadastro de item: não existe no PDV

- O 404 do bipe diz `produto não encontrado: <código> — cadastro rápido ainda não disponível`
  (`SaleScreen.tsx`, linha 428).
- O cadastro completo hoje é só `POST /api/v1/products` (`product.write`, que o OPERADOR não tem —
  seed da `V3__rbac.sql`), e a retaguarda web que o faria é o passo 1204, ainda não iniciado.

### 2.3 Tela que "dança" e envelhece

1. **Seleção pode sair da área visível:** a lista desenha só `items.slice(-10)` e `moveSelection`
   (`core/sale.ts`) permite qualquer índice — com 15 itens, subir até o 5º deixa o destaque fora da
   tela e `+`/`-`/DEL agem em item invisível.
2. **Quadro de altura variável:** totais, feedback e rodapé sobem/descem conforme os itens.
3. **Relógio congelado:** `App.tsx` passa `now={new Date()}` sem timer.
4. **Três linhas fixas de atalhos** (`SHORTCUT_ROWS`) sempre à vista, aplicáveis ou não.
5. **Sem tela cheia/restauração:** `terminal/src/index.tsx` só chama `render(<App />)`; o console
   atrás continua visível e o último quadro fica no scrollback.
6. **Cores soltas** por tela, sem tema nem fallback monocromático.
7. **Layout fixo em 80×24:** `useStdout()` só é usado para o bell.

### 2.4 O que já está bom e será preservado

`core/` puro e testado (1,2 mil linhas + 1,4 mil de teste), `api/` com o client tipado e a guarda de
problemas (1117), a resiliência, o E2E do 1120, o F11 de autoteste e `docs/leitores.md`.

---

## 3. O que fica, o que muda

| Camada | Hoje | Depois | Como |
| --- | --- | --- | --- |
| `terminal/src/core/` | TS puro | **igual** | nenhuma mudança de engine |
| `terminal/src/api/` + `packages/api-client` | fetch + tipos | **igual** | runtime-agnóstico |
| `terminal/src/ui/` | Ink 7 | **apagado no cut-over** | portado em ondas para `src/opentui/` |
| `useRawShortcuts` + tabela de sequências cruas do `core/keys` | necessários p/ F1–F12 na Ink | **não são mais necessários** | OpenTUI entrega `KeyEvent.name` (`f1`..`f12`) |
| `core/keys` (resolução por contexto) | — | **fica** | o adaptador mapeia `KeyEvent` → `KeyName` |
| `core/scanner` | rajada < 50 ms | **fica** | adaptador alimenta caractere a caractere com `performance.now()` |
| Testes de UI (11 arquivos, 6,4 mil linhas) | `ink-testing-library` | portados por onda | `@opentui/react/test-utils` (`testRender`) + `captureCharFrame()` |
| E2E (`e2e/pdv.e2e.test.tsx`) | dirigido pela Ink | reescrito no cut-over | dirigido pela UI nova |
| Runtime do PDV | Node 22 + `tsx` | Bun 1.3+ (recomendado) ou Node 26.4+ `--experimental-ffi` | decidido no spike |

---

## 4. Ganhos concretos da OpenTUI para o PDV (verificados na doc oficial)

| Recurso (React) | Resolve |
| --- | --- |
| `<input>` com `onInput`/`onSubmit`/`focused`, `placeholder`, `minLength`/`maxLength` e cores de foco | **F-01** leitura manual; buscas do F2/F6 |
| Listeners globais de teclado rodam **antes** do renderable focado (`stopPropagation`/`preventDefault`) | a rajada do leitor é detectada **antes** de o input focado engolir o código — o mesmo cuidado de hoje, sem o canal cru |
| `<scrollbox>` (automático em React, com scrollbar embutida) | **F-04** janela rolante da lista |
| `screenMode: "alternate-screen"` é o **padrão** e restaura a tela principal ao sair | **V-07** tela cheia e terminal limpo, sem ANSI à mão |
| `useTerminalDimensions` / `useOnResize` | **V-02** layout responsivo (e aviso de janela pequena) |
| `useTimeline` | **V-06** spinner/feedback animado |
| `usePaste` (bracketed paste) | colar código de barras/código interno |
| Clipboard/OSC 52 e selection handler | copiar o código bruto no F11 (se o terminal suportar) |
| `renderer.on("theme_mode")`, paleta e cores por célula | tema claro/escuro e glifos com semântica |
| Render sob demanda + `targetFps`/`maxFps` | PDV parado não gasta CPU |
| `@opentui/react/test-utils` + `mockInput`/`mockMouse` (F1–F12 inclusos) | testes de tela da UI nova |
| `@opentui/keymap` (opcional) | camadas de atalho por foco, se os listeners diretos ficarem difíceis |

Cuidados conhecidos (entram nos passos):

- `<input>` **não tem máscara de senha** — o login precisa de campo mascarado próprio (a lógica
  atual do `LoginScreen` serve de referência);
- `Slider`, `ScrollBar`, `TextTable`, `FrameBuffer` e `EmbeddedTerminal` **não existem em React**
  (só Core) — nada disso é necessário aqui; a scrollbar do `<scrollbox>` é embutida;
- nomes de tecla são minúsculos e canônicos (`return`, `escape`, `f12`).

---

## 5. Riscos e o gate do spike (1121)

1. **Runtime no caixa:** Bun 1.3+ é o caminho suportado; Node exigiria 26.4 + `--experimental-ffi`
   e a evidência oficial de Node é só Linux x64 (artefato Windows existe, sem esteira). Instalar o
   Bun na máquina do caixa é decisão operacional.
2. **Testes:** os exemplos oficiais usam `bun:test`; nosso stack é Vitest. O spike precisa dizer se
   o `testRender` roda sob o Vitest atual (com o runtime/flag certo) ou se a UI OpenTUI tem runner
   próprio (`bun test`) — duas configs é custo recorrente.
3. **FFI nativo no Windows:** antivírus/permissões e tempo de start.
4. **Leitor × input focado:** provar que a rajada é interceptada globalmente e não vira texto.
5. **Paridade de operação:** F1–F12, `3*`, bell, 80×24, terminal sem cores.
6. **Prazo:** é uma fase (spike + fundação + ~5 ondas + cut-over), não um passo de tarde.

**Go/no-go do spike:** relatório curto com (a) instalação/start no Windows do caixa; (b) frames em
80×24 e 120×40; (c) F-keys e rajada do leitor; (d) `testRender` sob o runner escolhido; (e) venda de
50 itens fluida; (f) plano de rollback. Sem os seis, não se apaga a Ink.

---

## 6. Polimento adiado (backlog pós-cut-over, na UI nova)

Nada disto é pré-requisito da migração; tudo continua desejado (numeração `D-xx` só para não se
perder):

| ID | Item | Observação |
| --- | --- | --- |
| D-01 | Feedback efêmero (sucesso/aviso expiram; falha fica) + spinner | usa `useTimeline` |
| D-02 | Painel de totais/troco maior, com fundo e destaque | `<text>` com estilo |
| D-03 | Estados vazios e tela de sucesso com a marca | `<ascii-font>` opcional |
| D-04 | Barra de status "final", refinada com o uso | a base entra no 1125 |
| D-05 | Tema claro/escuro pela detecção do terminal | `theme_mode`/`palette` do renderer |
| D-06 | Quantidade digitada de uma vez (F-06 da proposta anterior) | `=` + número + ENTER |
| D-07 | Vincular código novo a produto existente (F-03) | backend maior (`product_barcodes`) |
| D-08 | `@opentui/keymap` se os listeners diretos crescerem | não instalar por padrão |
| D-09 | Copiar a leitura do F11 via OSC 52 | depende do terminal |
| D-10 | Modo compacto/treinamento, sons além do bell | só com dor real |

---

## 7. Passos propostos — Fase 11b — OpenTUI React

Cada passo é um commit; a ordem recomendada é a da tabela. O 1122 (backend) é independente e pode
rodar em paralelo ao spike/fundação.

| Passo | Título | Objetivo / Implementar | Testes/aceite | Commit sugerido |
| --- | --- | --- | --- | --- |
| **1121** | **Spike OpenTUI (go/no-go)** | Provar engine no caixa: Bun, render 80×24, F1–F12, rajada do leitor antes do input, `<scrollbox>` com 50 itens, `testRender`; app mínimo **fora do fluxo** (worktree/branch), sem tocar no PDV | Relatório com os 6 itens do §5 e decisão go/no-go registrada | `chore(tui): avalia OpenTUI com spike no caixa` (se entrar no main; senão, branch) |
| **1122** | **Cadastro rápido — backend** | `POST /api/v1/products/quick` (`name`, `barcode`, `price`, `unit`), permissão nova `product.quick_create` (migration `V22`), auditoria `PRODUCT_QUICK_CREATED`, OpenAPI + `schema.d.ts` | RestAssured 201/403/409/400 + auditoria; `./mvnw verify` verde | `feat(catalog): adiciona cadastro rapido de produto` |
| **1123** | **Fundação da UI OpenTUI** | `terminal/src/opentui/`: entry com `createCliRenderer` + `createRoot`, tema (`theme.ts`), regiões de layout, adaptadores `KeyEvent`→`core/keys` e `KeyEvent`→scanner, foco, error boundary, shutdown (`renderer.destroy` em toda saída), `jsxImportSource`, script `start:opentui` (Bun) | teste de fumaça renderiza o shell em 80×24; adaptadores puros testados; `tsc --noEmit` | `feat(tui): cria fundacao da UI em OpenTUI` |
| **1124** | **Entrada: login, abertura de caixa e erro** | Portar `LoginScreen` (senha mascarada própria), seleção de caixa, `OpeningCashScreen` (máscara de dinheiro) e `ErrorScreen`; reaproveitar os dublês de API | testes de tela equivalentes aos da Ink (login OK/credencial inválida, valor inválido, retry) | `feat(tui): porta entrada do PDV para OpenTUI` |
| **1125** | **Tela de venda** | Cabeçalho + relógio vivo, lista com `<scrollbox>` e janela que segue a seleção (F-04), **leitura manual** com `<input>` + rajada interceptada globalmente (F-01), totais, feedback/spinner, barra de status base | testes: bipe, digitação manual, `3*`, scroll/seleção, 80×24 e 120×40 | `feat(tui): migra a tela de venda para OpenTUI` |
| **1126** | **Modais** | F1 ajuda, F2 consulta de preço, F5 desconto, F6 cliente, F4 cancelar venda, F12 trocar operador, F7/F8 gaveta, confirmação do DEL; `ModalFrame` único | testes de cada modal (recusa no modal, retry, ESC, lista com setas) | `feat(tui): migra os modais para OpenTUI` |
| **1127** | **Pagamento, sucesso e fechamento** | `PaymentScreen`, `SaleSuccessScreen` (troco em destaque) e `ClosingCashScreen` (contado/diferença) | pagamento parcial/múltiplo, troco, bloqueio por venda aberta, ENTER da próxima venda | `feat(tui): migra pagamento e fechamento para OpenTUI` |
| **1128** | **Cadastro rápido — modal (F-02)** | No 404 do bipe/linha digitada, abrir o modal com o código travado usando o endpoint do 1122; no sucesso, reenviar o bipe; recusa no modal | teste do fluxo "desconhecido → cadastra → item na venda"; E2E estendido | `feat(tui): cadastra produto rapido pelo PDV` |
| **1129** | **Autoteste do leitor 2.0 (opcional)** | Histórico das 5 últimas leituras + diagnóstico do transporte (lento, sem terminador, layout, erro do servidor) | testes puros do diagnóstico + tela | `feat(tui): diagnostica o leitor no autoteste` |
| **1130** | **Cut-over e limpeza** | `npm start`/`pdv.cmd` → Bun + entry OpenTUI; E2E reescrito para a UI nova; apagar `src/ui` Ink, `ink`, `ink-testing-library` e `useRawShortcuts`; atualizar `docs/referencias.md`, README e `docs/leitores.md`; ajustar `engines` | E2E completo verde contra backend real **na UI nova**; nenhum arquivo Ink restante | `chore(tui): aposenta a Ink e ativa a UI em OpenTUI` |

**Regras da transição:** durante a fase, a Ink segue atendendo o caixa sem receber recurso novo (só
correção crítica); a UI nova nasce em `start:opentui` e o cut-over acontece quando o fluxo atual
estiver em paridade + F-01/F-04 na venda. Depois do cut-over, o polimento §6 acontece na UI nova.

---

## 8. O que **não** fazer

- Manter as duas UIs **para sempre** — a transição tem prazo e termina apagando a Ink.
- Migrar "só o visual" sem o spike: runtime, leitor e testes são o risco, não o desenho.
- Criar uma camada de UI genérica "para as duas engines" (abstração que ninguém pediu).
- Instalar `@opentui/keymap`, `@opentui/three`, `@opentui/qrcode`, áudio e imagens sem dor real.
- Levar tema configurável, mouse como interação principal, animações de propaganda ou modo offline
  para dentro do PDV.
- Trocar `core/` ou `api/` por "versões OpenTUI": eles já são agnósticos e testados.

---

## 9. Decisões pendentes do dono

1. **Bun no caixa:** confirmar a instalação do Bun (recomendado) ou tentar Node 26.4 +
   `--experimental-ffi`; o spike mede os dois, mas a decisão é operacional.
2. **Test runner da UI nova:** se o spike mostrar que o `testRender` não roda bem no Vitest,
   aceitamos `bun test` para a UI OpenTUI (duas configs) ou mantemos um runner só?
3. **Cadastro rápido (1122):** permissão nova `product.quick_create` para OPERADOR e campos
   `nome + código + preço + unidade`; preço pelo operador é aceitável? Limitar a `UN` na 1ª versão?
4. **Cut-over:** apagar a Ink assim que houver paridade do fluxo (recomendado) ou manter um período
   com as duas entradas?
5. **Leitura manual:** digitação aparece automaticamente (recomendado) ou só depois de uma tecla
   dedicada?

---

## 10. Referências

- `docs/plano-tecnico.md` §11.2/§11.3 — arquitetura da TUI, UX de teclado e leitor.
- `docs/roadmap.md` — Fase 11 (1101–1120 concluídos) e Fase 12 (1201–1212 pendentes).
- `docs/leitores.md` — leitor = teclado, rajada < 50 ms, etiqueta de balança, F11.
- `terminal/src/ui/SaleScreen.tsx`, `terminal/src/core/scanner.ts`, `terminal/src/core/sale.ts`,
  `terminal/src/ui/App.tsx`, `terminal/src/index.tsx` — pontos citados no diagnóstico.
- `backend/src/main/resources/db/migration/V3__rbac.sql` — permissões do OPERADOR.
- `backend/src/main/java/com/minimarket/catalog/api/CreateProductRequest.java` — campos do cadastro.
- OpenTUI (consultado em 2026-09-25, docs locais na skill `opentui`):
  <https://opentui.com/docs> · <https://opentui.com/docs/getting-started/runtime-support> ·
  <https://opentui.com/docs/bindings/react> · <https://opentui.com/docs/core-concepts/keyboard> ·
  <https://opentui.com/docs/components/input> · <https://opentui.com/docs/core-concepts/renderer> ·
  <https://opentui.com/docs/core-concepts/testing> · <https://github.com/anomalyco/opentui>.

---

## Anexo A — Plano B: se o spike der no-go (polimento na Ink)

Se o gate do 1121 falhar, a Fase 11b vira a proposta da revisão 1 (polimento na engine atual), com
numeração e detalhes próprios, nesta ordem: **1121** leitura manual (`core/manualCode.ts` + linha
`Código:` na `SaleScreen`); **1122/1123** cadastro rápido (backend + modal Ink); **1124** janela
rolante e item tocado; **1125** relógio vivo; **1126** tema, glifos e `ModalFrame`; **1127** barra de
status contextual; **1128** regiões fixas/responsivas; **1129** alternate screen por ANSI no
`index.tsx`; **1130** feedback efêmero e spinner; **1131** autoteste do leitor 2.0. Os itens do §6
(D-01 a D-10) valem nos dois caminhos — na Ink eles são o produto final, na OpenTUI são o polimento
pós-cut-over.
