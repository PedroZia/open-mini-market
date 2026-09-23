---
name: Tela da TUI (Ink)
description: Cria tela, componente, modal ou atalho da TUI em Ink seguindo a arquitetura do terminal — core puro, sem cálculo de negócio, teclado F1–F12, leitor de código de barras tratado como teclado. Use quando o passo for da Fase 11 ou pedir tela, componente, modal, atalho, bipe ou interação do PDV.
---

# Tela da TUI (Ink)

## Regra de ouro

**Nenhum cálculo de negócio na TUI** (BR-12): sem total, desconto, troco, quantidade de etiqueta ou
parsing de código de barras. A TUI envia intenção e exibe a resposta do servidor.

## Estrutura

```text
terminal/src/
  core/   lógica pura (state, reducer, keys, scanner, totals) — testável sem render
  api/    client gerado do OpenAPI
  ui/     componentes Ink
```

- Estado em união discriminada: `Login`, `OpeningCash`, `SaleOpen`, `Paying`, `ClosingCash`, `Error`.
- Toda transição nasce em `core/` com teste unitário; `ui/` só desenha e despacha ações.

## Teclado e leitor

- **Leitor de código de barras é teclado**: rajada de caracteres com intervalo < 50 ms terminada em
  `ENTER`/`TAB`, tratada independentemente do foco (exceto em modais bloqueantes).
- Envie o `barcode` **bruto** ao servidor (BR-14) — nunca interprete GTIN, código interno ou etiqueta de
  balança no cliente.
- Multiplicador: `3*` + bipe → `quantity = 3` na mesma chamada.
- Atalhos: `F1` ajuda · `F2` consulta de preço · `F3` cancelar item · `F4` cancelar venda · `F5` desconto ·
  `F6` cliente · `F7` sangria · `F8` suprimento · `F9` finalizar/pagamento · `F10` fechar caixa ·
  `F11` autoteste do leitor · `F12` trocar operador · `ENTER` confirma · `ESC` fecha modal/volta ·
  setas navegam itens · `+`/`-` alteram quantidade · `DEL` remove item.
- Conflito de tecla se resolve **por contexto** e isso é testado em `core/keys.ts`.

## Testes

- `core/`: unitário puro, sem render.
- `ui/`: `ink-testing-library` — asserte texto renderizado e reação a teclas.
- Gate: `cd terminal && npm test && npx tsc --noEmit`.

## Restrições

- 80×24 no mínimo, sem mouse, pt-BR, cores com fallback monocromático.
- **Sem modo offline**: rede caiu → bloqueia e mostra erro; nada de fila local.
- Sem biblioteca nova de UI sem justificar no commit.
- Erro de rede: retry manual, sem perder o estado da venda em andamento.
