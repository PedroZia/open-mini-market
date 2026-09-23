---
name: Feature da retaguarda web
description: Cria uma feature da retaguarda React (Vite + TanStack Query + React Hook Form/Zod + Tailwind) na estrutura features/<feature>/, com tipos vindos do OpenAPI e testes Vitest/RTL. Use quando o passo for da Fase 12 ou pedir tela, formulário, tabela, página ou rota do web.
---

# Feature do web (retaguarda)

Escopo do web: **administração**. Tela de venda é a TUI — não replique aqui.

## Estrutura

```text
web/src/features/<feature>/
  api/         chamadas tipadas (client gerado do OpenAPI)
  hooks/       queries e mutations do TanStack Query
  pages/       telas ligadas às rotas
  components/  tabela, formulário, modal da feature
```

## Regras

- **Tipos vêm do client gerado** (`packages/api-client`, via `openapi-typescript`). Não declare payload à
  mão; se o tipo não existe, regenere a partir do OpenAPI.
- Estado de servidor no TanStack Query (`staleTime` curto, invalidação explícita após mutação); estado de
  UI no componente. **Sem Redux/Zustand.**
- Formulário: React Hook Form + Zod, com o schema reaproveitado na exibição de erro. Validação no cliente
  é conveniência — o servidor valida de novo.
- Dinheiro: exiba formatado em pt-BR, envie o número decimal cru. **Nunca recalcule** total, desconto ou
  saldo no front.
- Erros: trate `problem+json` pelo campo `code` com mensagem clara. `403` mostra estado de "sem permissão",
  não tela em branco.
- Rotas protegidas por sessão + permissão, no mesmo modelo do backend.
- Tabela: paginação do servidor; trate sempre loading, vazio e erro.
- Acessibilidade básica: `label` em todo input, foco visível, contraste, navegação por teclado.

## Testes

- Vitest + Testing Library para componente/hook; Playwright para o fluxo principal da feature.
- Gate: `cd web && npm test && npx tsc --noEmit`.

## Proibido

- Regra de negócio no front.
- Biblioteca de UI ou de estado nova sem justificar no commit.
- Duplicar lógica que já existe no backend.
