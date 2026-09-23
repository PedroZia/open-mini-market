---
name: Teste de concorrência
description: Escreve testes de concorrência do PDV com PostgreSQL real — última unidade em estoque, dois caixas abrindo o mesmo terminal, fechamento com venda aberta, replay idempotente, claim de fila com SKIP LOCKED. Use quando o passo pedir teste de concorrência, disputa, lock, race ou "dois operadores ao mesmo tempo".
---

# Teste de concorrência

A garantia testada é do **PostgreSQL** (lock de linha, unique index parcial, `SKIP LOCKED`). Por isso:
nunca mock de banco, nunca H2 — `@QuarkusTest` + Dev Services/Testcontainers com PostgreSQL real.

## Padrão

```java
int threads = 8;
var start = new CountDownLatch(1);
try (var pool = Executors.newFixedThreadPool(threads)) {
    var futures = IntStream.range(0, threads)
        .mapToObj(i -> pool.submit(() -> { start.await(); return executar(i); }))
        .toList();
    start.countDown();
    // asserts
}
```

- Cada thread precisa de **contexto próprio**: transação própria; em teste HTTP, requisição própria com
  seu token.
- `CountDownLatch` para a largada ser simultânea de verdade.
- Efeito assíncrono (worker, fila) → `Awaitility`, nunca `Thread.sleep`.
- Asserte **contagem de efeitos**, não ordem: exatamente 1 vencedor e N-1 com o erro esperado.
- Asserte o **invariante final no banco**, não só o status HTTP: saldo, `balance_after`,
  `sum(movimentos) = saldo`, uma linha de ledger por movimento aplicado.

## Casos obrigatórios do projeto

| Cenário | Garantia esperada |
| --- | --- |
| Última unidade em estoque | `FOR UPDATE` ordenado por `product_id` (evita deadlock); `allow_negative_stock=false` → `422 INSUFFICIENT_STOCK` |
| Dois `open` no mesmo caixa | unique index parcial `where status='OPEN'` → `409 CASH_REGISTER_ALREADY_OPEN` |
| Fechamento com venda aberta | `409`; a sessão não fecha |
| Replay de `Idempotency-Key` | 1 efeito, 2 respostas iguais; corpo diferente → `409 IDEMPOTENCY_KEY_REUSED` |
| Claim de fila (Fase 14) | dois workers pegam lotes disjuntos (`SKIP LOCKED`) |

Passos do roadmap com este tipo de teste: **613** (caixa) e **707** (estoque).

## Higiene

- Teste flaky é bug do código ou do teste. **Nunca** "conserte" aumentando `sleep` ou marcando
  `@Disabled`.
- Rode o teste repetidamente (ex.: 5×) antes de considerar pronto — concorrência que passa uma vez não
  prova nada.
- `@DisplayName` em pt-BR descrevendo o invariante, ex.: `"não vende a última unidade para dois caixas"`.
