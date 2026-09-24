# Catálogo de auditoria

Referência de consulta do log de auditoria do PDV (passo 1005): o que existe em `audit_events`, o que
cada `action` significa, quais campos vêm em `details` e como investigar uma operação sem adivinhar.
O plano está em [`plano-tecnico.md`](plano-tecnico.md) §7; aqui é o catálogo conferido contra o código
de `backend/src/main/java`.

**Última conferência:** 39 ações distintas emitidas no código e 39 documentadas neste arquivo — a
conferência, nos dois sentidos e com os comandos de busca, está em [Manutenção](#6-manutenção).

## 1. Visão geral

`audit_events` é um log **append-only**: a aplicação só insere e lê (nunca altera nem apaga) e cada
evento é um fato consumado do passado — por isso **não há FK** para as entidades citadas (a migration
`V6` explica: o log não bloqueia nem é bloqueado pela operação de negócio, e o id guardado é
histórico).

| Propriedade | Como funciona |
| --- | --- |
| **Mesma transação** | `AuditRecorder.record(...)` é chamado de dentro do caso de uso (`@Transactional`), sem `try/catch`: se a gravação do evento falhar, a operação inteira é desfeita. Auditoria que perde evento em silêncio é pior do que erro na cara. Exceção deliberada: `ACCESS_DENIED` (ver abaixo). |
| **Ator e contexto** | Quem chama não repassa ator, sessão, loja, caixa, correlação nem IP: isso vem do `OperationContext` da requisição, preenchido pelo `OperationContextFilter` a partir da identidade autenticada. |
| **Origem (`source`)** | `TUI`/`WEB` = cliente da sessão autenticada; `API` = requisição HTTP sem sessão de PDV (login, meta — é o valor do login que falha); `SYSTEM` = operação que não nasceu de requisição nenhuma (tarefas internas e inicializadores). O `check` da coluna aceita os quatro. |
| **`id` e `occurred_at`** | O `id` é `bigint identity` (exceção ao UUIDv7: o log é sequencial) e o `occurred_at` é o `now()` do PostgreSQL, não o relógio da aplicação: **todos os eventos de uma mesma transação compartilham o mesmo instante**. A ordem fina é o `id` — a consulta ordena por `occurred_at` e desempata por `id`. |
| **Idempotência** | Replay de `Idempotency-Key` devolve a resposta original sem reexecutar o caso de uso: **não gera evento novo**. |
| **Sessão de caixa** | `cash_session_id` é preenchido quando o caso de uso conhece a sessão (caixa, venda e pagamento — passo 1006): é o id explícito que o `AuditRecorder` recebe, nunca uma consulta por requisição. Usuários, auth e estoque manual continuam com a coluna nula, porque não pertencem a um turno de caixa. |
| **Movimentos internos** | Baixa de estoque da venda e entrada de dinheiro da venda são movimentos de ledger/estoque, não eventos de auditoria próprios: o rastro da venda é o `SALE_COMPLETED` (com `paymentsByMethod`) mais os movimentos consultáveis de estoque e caixa. |

O caso de uso chama o recorder assim:

```java
auditRecorder.record(action, entityType, entityId, reason, details);            // sem sessão de caixa
auditRecorder.record(action, entityType, entityId, reason, details, cashSessionId);  // passo 1006
```

- `entityType`/`entityId`: o alvo do evento (`SALE`, `PRODUCT`, `CASH_SESSION`, `USER`, `AUTH_SESSION`,
  `CUSTOMER`, `ROLE`); nulos quando a ação não tem entidade (ex.: `LOGIN_FAILED` de username
  inexistente, `ACCESS_DENIED`).
- `reason`: o motivo **humano** informado na operação (cancelamento, sangria, desconto, ajuste…);
  nulo nas ações que não pedem motivo.
- `details`: só o antes/depois mínimo do §7.2 — nunca o objeto inteiro. `null` vira `{}` (jsonb).

**`ACCESS_DENIED` é o único evento fora da transação da operação** (`REQUIRES_NEW`, porque quando ele
é gravado a transação do caso de uso já foi desfeita pelo 403) — detalhes na §5.

## 2. Catálogo de eventos

Uma tabela por módulo. `entityId` é o alvo do evento citado na coluna `entityType`/`entityId`;
"emissor" é a classe que chama o `AuditRecorder` (não o endpoint).

### auth

| `action` | Emissor (quando) | `entityType`/`entityId` | `reason` | `details` |
| --- | --- | --- | --- | --- |
| `LOGIN_SUCCESS` | `LoginUseCase.recordLoginSuccess` — login aceito | `USER` / id do usuário | — | — (`{}`) |
| `LOGIN_FAILED` | `LoginUseCase.recordLoginFailure` — credencial recusada (senha errada, usuário inexistente/inativo ou a falha que atinge o limite e ainda responde 401) | `USER` / id do usuário, **nulo** se o username não existe | — | `username`, `client` (`TUI`/`WEB`) |
| `LOGIN_LOCKED` | `LoginUseCase.recordLoginFailure` — tentativa recusada por `locked_until` no futuro (423) | `USER` / id do usuário | — | `username`, `client` |
| `LOGOUT` | `LogoutUseCase.execute` — encerramento da própria sessão | `AUTH_SESSION` / id da sessão encerrada | — | — (`{}`) |
| `SESSION_REVOKED` | `RevokeSessionUseCase.execute` — revogação de uma sessão específica (o dono ou quem tem `user.session.revoke`) | `AUTH_SESSION` / id da sessão alvo | — | — (`{}`) |
| `SESSION_REVOKED` | `UserAccessChangedObserver` — corte em massa, disparado por `DisableUserUseCase` (`USER_DISABLED`), `ResetPasswordUseCase` (`PASSWORD_RESET`) e `RevokeUserSessionsUseCase` (`ADMIN_REVOKE`) | `USER` / id do usuário afetado | motivo do corte (`USER_DISABLED`, `PASSWORD_RESET`, `ADMIN_REVOKE`) | `reason`, `revokedCount` |
| `PASSWORD_CHANGED` | `ChangeOwnPasswordUseCase.execute` — troca da própria senha (passo 1006: exige a senha atual, grava o hash novo e derruba as demais sessões) | `USER` / id do usuário da sessão | — | `username` |

> O corte em massa grava **um** evento por execução, apontando o usuário (não há uma sessão única a
> apontar); o `revokedCount` pode ser `0` quando não havia sessão viva. `LOGIN_FAILED`/`LOGIN_LOCKED`
> não têm ator na gravação (a rota é pública e o contexto chega anônimo): o username tentado fica em
> `details` e o IP/`requestId` no contexto da requisição.
>
> A troca da própria senha (`PASSWORD_CHANGED`) é o evento do usuário que a pediu — a sessão que fez
> a troca sobrevive e as demais caem com `revoked_reason = PASSWORD_CHANGED`, mas o corte em si não
> tem evento próprio: o rastro dele é a coluna `revoked_reason` de cada sessão derrubada. `details`
> leva só o `username` — senha e hash nunca entram no log.

### users

| `action` | Emissor (quando) | `entityType`/`entityId` | `reason` | `details` |
| --- | --- | --- | --- | --- |
| `USER_CREATED` | `CreateUserUseCase.execute` | `USER` / id do usuário | — | `username`, `displayName`, `roles` |
| `USER_UPDATED` | `UpdateUserUseCase.execute` | `USER` / id do usuário | — | `before{displayName, roles}`, `after{displayName, roles}` |
| `USER_DISABLED` | `DisableUserUseCase.execute` | `USER` / id do usuário | — | `before{status}`, `after{status}` |
| `USER_ENABLED` | `EnableUserUseCase.execute` — só quando reativa de fato (usuário já ativo não inventa evento) | `USER` / id do usuário | — | `before{status: DISABLED}`, `after{status}` |
| `PASSWORD_RESET` | `ResetPasswordUseCase.execute` | `USER` / id do usuário | — | `username`, `mustChangePassword` |
| `ROLE_PERMISSIONS_CHANGED` | `ReplaceRolePermissionsUseCase.execute` — só quando o conjunto de permissões muda | `ROLE` / **nulo** (role não tem UUID) | — | `role` (código), `before` (permissões), `after` (permissões) |

`USER_DISABLED` e `PASSWORD_RESET` dispararem o corte de sessões: o mesmo request grava os dois
eventos (`USER_DISABLED`/`PASSWORD_RESET` e o `SESSION_REVOKED` do observer) na mesma transação, com o
mesmo `occurred_at` e desempate pelo `id`.

### catalog

| `action` | Emissor (quando) | `entityType`/`entityId` | `reason` | `details` |
| --- | --- | --- | --- | --- |
| `PRODUCT_CREATED` | `CreateProductUseCase.execute` | `PRODUCT` / id do produto | — | `name`, `barcode`, `price` |
| `PRODUCT_UPDATED` | `UpdateProductUseCase.execute` | `PRODUCT` / id do produto | — | `before{name, categoryId, unit, description, minQuantity}`, `after{...}` |
| `PRODUCT_PRICE_CHANGED` | `ChangeProductPriceUseCase.execute` (`PATCH /products/{id}/price`) | `PRODUCT` / id do produto | motivo informado na alteração | `before{price}`, `after{price}` |
| `PRODUCT_DISABLED` | `DisableProductUseCase.execute` | `PRODUCT` / id do produto | — | `before{active}`, `after{active}` |
| `PRODUCT_ENABLED` | `EnableProductUseCase.execute` | `PRODUCT` / id do produto | — | `before{active}`, `after{active}` |

### inventory

| `action` | Emissor (quando) | `entityType`/`entityId` | `reason` | `details` |
| --- | --- | --- | --- | --- |
| `STOCK_RECEIVED` | `RegisterStockReceiptUseCase.execute` (`POST /stock/{productId}/receipts`) | `PRODUCT` / id do produto | motivo informado | `before{quantity}`, `after{quantity}`, `quantity`, `unitCost` (nulo quando não informado) |
| `STOCK_ADJUSTED` | `AdjustStockUseCase.execute` (`POST /stock/{productId}/adjustments`) | `PRODUCT` / id do produto | motivo informado | `before{quantity}`, `after{quantity}` |

O alvo é o **produto**, não o movimento: para ver o saldo e os movimentos do ledger, use o detalhe
`GET /api/v1/stock/{productId}` (saldo atual e os últimos vinte movimentos).

### cash

| `action` | Emissor (quando) | `entityType`/`entityId` | `reason` | `details` |
| --- | --- | --- | --- | --- |
| `CASH_SESSION_OPENED` | `OpenCashSessionUseCase.execute` | `CASH_SESSION` / id da sessão | — | `cashRegisterId`, `openingAmount` |
| `CASH_SESSION_CLOSED` | `CloseCashSessionUseCase.execute` | `CASH_SESSION` / id da sessão | — | `countedAmount`, `expectedAmount`, `differenceAmount` |
| `CASH_SUPPLY` | `RecordSupplyUseCase.execute` (`POST /cash-registers/{id}/supplies`) | `CASH_SESSION` / id da sessão | motivo informado | `amount`, `expectedBefore`, `expectedAfter` |
| `CASH_WITHDRAWAL` | `RecordWithdrawalUseCase.execute` (`POST /cash-registers/{id}/withdrawals`) | `CASH_SESSION` / id da sessão | motivo informado | `amount`, `expectedBefore`, `expectedAfter`, `aboveExpected` |

`expectedBefore`/`expectedAfter` são o esperado da sessão antes e depois do movimento (regra de
`CashSessionAmounts`); `aboveExpected` marca a sangria maior que o saldo esperado — o alerta que a
investigação procura. A coluna `cash_register_id` do evento é o caixa **da sessão autenticada**
(vem do `OperationContext`), não o caixa do path. `cash_session_id` é a sessão do evento (passo 1006):
é ela que o filtro `cashSessionId` usa para trazer o turno inteiro, com vendas e pagamentos junto.

### sales

| `action` | Emissor (quando) | `entityType`/`entityId` | `reason` | `details` |
| --- | --- | --- | --- | --- |
| `SALE_CREATED` | `CreateSaleUseCase.execute` (`POST /sales`) | `SALE` / id da venda | — | `number`, `cashSessionId`, `cashRegisterId` |
| `SALE_ITEM_ADDED` | `AddSaleItemUseCase.execute` | `SALE` / id da venda | — | `productId`, `barcode`, `quantity`, `subtotal`, `discountAmount`, `total`, `itemCount` |
| `SALE_ITEM_REMOVED` | `RemoveSaleItemUseCase.execute` | `SALE` / id da venda | — | `productId`, `quantity`, `lineTotal`, `subtotal`, `discountAmount`, `total`, `itemCount` |
| `SALE_ITEM_QUANTITY_CHANGED` | `ChangeSaleItemQuantityUseCase.execute` | `SALE` / id da venda | — | `productId`, `previousQuantity`, `quantity`, `lineTotal`, `subtotal`, `discountAmount`, `total`, `itemCount` |
| `SALE_DISCOUNT_APPLIED` | `ApplyDiscountUseCase.execute` (`PUT /sales/{id}/discount`) | `SALE` / id da venda | motivo do desconto (BR-04) | `type`, `value`, `discountAmount`, `total` |
| `SALE_DISCOUNT_REMOVED` | `RemoveDiscountUseCase.execute` — só se havia desconto | `SALE` / id da venda | motivo do desconto que saiu | `type`, `value`, `discountAmount`, `total` |
| `PAYMENT_ADDED` | `AddPaymentUseCase.execute` | `SALE` / id da venda | — | `paymentId`, `method`, `amount`, `tenderedAmount`, `changeAmount`, `paidAmount` |
| `PAYMENT_CANCELLED` | `CancelPaymentUseCase.execute` — só se o pagamento estava aprovado | `SALE` / id da venda | — | `paymentId`, `method`, `amount`, `paidAmount` |
| `SALE_COMPLETED` | `CompleteSaleUseCase.execute` (`POST /sales/{id}/complete`) | `SALE` / id da venda | — | `number`, `total`, `paidAmount`, `changeAmount`, `paymentsByMethod{METHOD: valor}` |
| `SALE_CANCELLED` | `CancelSaleUseCase.execute` — só se a venda não estava cancelada | `SALE` / id da venda | motivo do cancelamento (BR-04) | `status`, `subtotal`, `discountAmount`, `total`, `itemCount` |
| `SALE_CUSTOMER_LINKED` | `LinkCustomerUseCase.execute` | `SALE` / id da venda | — | `customerId`, `customerName` |
| `SALE_CUSTOMER_UNLINKED` | `UnlinkCustomerUseCase.execute` | `SALE` / id da venda | — | `customerId` |

Todos os eventos de item, pagamento e desconto apontam para a **venda** (é ela o agregado): o
`paymentId` identifica a linha do pagamento dentro de `details`, e os totais de cada evento são o
estado recalculado pelo servidor (BR-12), com o preço como snapshot do item capturado quando ele
entrou na venda (BR-01) — nunca o preço atual do produto. Todos eles, mais o `SALE_CREATED` e o
`SALE_COMPLETED`, gravam também o `cash_session_id` da venda (passo 1006): é o vínculo com o turno do
caixa, que o filtro `cashSessionId` usa (§3.5).

### customers

| `action` | Emissor (quando) | `entityType`/`entityId` | `reason` | `details` |
| --- | --- | --- | --- | --- |
| `CUSTOMER_CREATED` | `CreateCustomerUseCase.execute` | `CUSTOMER` / id do cliente | — | `name`, `taxId` |
| `CUSTOMER_UPDATED` | `UpdateCustomerUseCase.execute` | `CUSTOMER` / id do cliente | — | `before{name, taxId, phone, email, notes}`, `after{...}` |
| `CUSTOMER_DISABLED` | `DisableCustomerUseCase.execute` | `CUSTOMER` / id do cliente | — | `before{active}`, `after{active}` |

### audit/acesso

| `action` | Emissor (quando) | `entityType`/`entityId` | `reason` | `details` |
| --- | --- | --- | --- | --- |
| `ACCESS_DENIED` | `AccessDeniedAuditRecorder.record`, observando o `AccessDeniedEvent` que o `BusinessExceptionMapper` publica a **cada 403** | — (nulos) | — | `route`, `method`, `permission` |

`permission` é o código exigido (`user.read`, `sale.discount.apply`, …) quando o 403 nasceu de uma
checagem de permissão; é **nulo** quando o 403 não tem uma permissão única por trás — venda de outro
caixa (`SaleAccessGuard`) ou sessão sem caixa vinculado (`CreateSaleUseCase`), por exemplo. Como o
403 do próprio `GET /audit-events` também é um `ForbiddenException`, um OPERADOR tentando ler o log
gera um `ACCESS_DENIED` citando `audit.read`.

## 3. Exemplos de investigação

Todas as consultas usam `GET /api/v1/audit-events` com um token que tenha `audit.read` (GERENTE ou
ADMIN; OPERADOR recebe 403). Os eventos de cada request da linha do tempo têm `occurredAt` distintos
(cada request é uma transação); quando um mesmo request grava dois eventos, eles compartilham o
instante e a ordem é o `id`.

### 3.1 Venda com desconto — a vida da venda

Fluxo que gera os eventos (o mesmo do teste `AuditSaleTimelineTest`):

```http
POST /api/v1/cash-registers/{cashRegisterId}/open   Idempotency-Key: ...   {"openingAmount": 100.00}
POST /api/v1/sales                                  Idempotency-Key: ...
POST /api/v1/sales/{saleId}/items                   {"barcode": "789102...", "quantity": 2}
PUT  /api/v1/sales/{saleId}/discount                {"type": "PERCENT", "value": 10, "reason": "cliente fidelidade"}
POST /api/v1/sales/{saleId}/payments                Idempotency-Key: ...   {"method": "CASH", "amount": 22.50, "tenderedAmount": 30.00}
POST /api/v1/sales/{saleId}/complete                Idempotency-Key: ...
```

Consulta (linha do tempo do começo para o fim):

```http
GET /api/v1/audit-events?entityType=SALE&entityId={saleId}&sort=occurredat,asc&size=100
```

Leitura do resultado — exatamente os seis eventos da venda, na ordem do fluxo:

| `action` | O que ler |
| --- | --- |
| `SALE_CREATED` | `details.number` (número da venda), `cashSessionId`/`cashRegisterId` do vínculo (BR-11) |
| `SALE_ITEM_ADDED` (×2) | `details.barcode` + `quantity` (o que foi bipado), `productId`, `subtotal`/`discountAmount`/`total` recalculados a cada item |
| `SALE_DISCOUNT_APPLIED` | `reason` = `"cliente fidelidade"` (o rastro humano), `details.type`/`value` e o desconto calculado em `discountAmount`/`total` |
| `PAYMENT_ADDED` | `details.paymentId`, `method` (`CASH`), `amount`, `tenderedAmount` e `changeAmount` |
| `SALE_COMPLETED` | `details.total`, `paidAmount`, `changeAmount` e `paymentsByMethod` (Σ dos aprovados por forma) |

Campos comuns (`actorUsername`, `source`, `occurredAt`, `ip`, `requestId`) mostram **quem** operou,
**de onde** (TUI/WEB) e sob **qual correlação** — o `requestId` casa com o `X-Request-Id` da resposta
original. O histórico da venda não inclui eventos de estoque/caixa porque a baixa e o movimento são
linhas de ledger, não eventos; para o dinheiro, consulte o caixa da sessão (`CASH_SESSION_CLOSED` e
`GET /api/v1/cash-sessions/{id}/summary`).

### 3.2 Sangria — quanto saiu e como estava o esperado

```http
POST /api/v1/cash-registers/{cashRegisterId}/withdrawals   Idempotency-Key: ...
{"amount": 100.00, "reason": "depósito bancário"}
```

```http
GET /api/v1/audit-events?entityType=CASH_SESSION&action=CASH_WITHDRAWAL&entityId={cashSessionId}&sort=occurredat,asc
```

Leitura do evento `CASH_WITHDRAWAL`: `reason` = `"depósito bancário"`; `details.amount` = `100.00`;
`details.expectedBefore`/`expectedAfter` = o esperado da sessão antes e depois (a variação é o próprio
`amount`); `details.aboveExpected` = `true` se a sangria passou do esperado — o sinal de alerta.
`actorUserId`/`actorUsername` identificam quem sangrou e `cashRegisterId` mostra o caixa da sessão.
Consultar a sessão inteira (só `entityId`, sem `action`) traz `CASH_SESSION_OPENED`, os
`CASH_SUPPLY`/`CASH_WITHDRAWAL` e o `CASH_SESSION_CLOSED` dela na mesma linha do tempo; o
`cashSessionId` da §3.5 é o mesmo recorte, com as vendas e pagamentos junto.

### 3.3 Alteração de preço — o antes e o depois

```http
PATCH /api/v1/products/{productId}/price
{"price": 12.50, "reason": "ajuste de fornecedor"}
```

```http
GET /api/v1/audit-events?entityType=PRODUCT&entityId={productId}&action=PRODUCT_PRICE_CHANGED&sort=occurredat,asc
```

Leitura: `reason` = `"ajuste de fornecedor"`; `details.before.price` = preço antigo e
`details.after.price` = preço novo (números decimais, ex.: `10.00` → `12.50`). O evento é o rastro de
**quem** mudou o preço e **por quê** — o valor praticado nas vendas antigas não muda (o preço é
snapshot no item, BR-01).

### 3.4 Falha/bloqueio de login e `ACCESS_DENIED`

Login recusado e conta bloqueada (o ator é anônimo: `actorUserId` e `actorUsername` vêm nulos):

```http
GET /api/v1/audit-events?action=LOGIN_FAILED&from=2026-09-24T00:00:00Z&to=2026-09-25T00:00:00Z&size=100
GET /api/v1/audit-events?action=LOGIN_LOCKED&from=2026-09-24T00:00:00Z&to=2026-09-25T00:00:00Z&size=100
```

Leitura: `details.username` = username tentado (o usuário pode nem existir — nesse caso `entityId` é
nulo), `details.client` = `TUI` ou `WEB`, `source` = `API` (rota pública, sem sessão) e `ip`/`requestId`
identificam a origem e a requisição. `LOGIN_FAILED` cobre a senha errada, o usuário inexistente/inativo
e também a falha que atinge o limite de tentativas (que ainda responde 401); `LOGIN_LOCKED` é a
tentativa recusada porque o bloqueio já estava valendo (423). Use `from`/`to` sempre — é o log que não
para de crescer — e `actorUserId` quando o usuário tentado existe.

Acesso negado por permissão:

```http
GET /api/v1/audit-events?action=ACCESS_DENIED&from=2026-09-24T00:00:00Z&to=2026-09-25T00:00:00Z
```

Leitura: `details.route` = caminho recusado (ex.: `/api/v1/users`), `details.method` = `GET`/`POST`/… e
`details.permission` = permissão exigida (ex.: `user.read`; nulo em 403 sem permissão única, como a
venda de outro caixa). O ator, a sessão, a loja e o IP vêm do `OperationContext` da requisição
recusada — diferente do `LOGIN_FAILED`, aqui o 403 pressupõe identidade autenticada (ou, no caso de
`audit.read`, um OPERADOR autenticado tentando ler o log).

### 3.5 Turno do caixa — o que aconteceu na sessão

```http
GET /api/v1/audit-events?cashSessionId={cashSessionId}&sort=occurredat,asc&size=100
```

O filtro por sessão (passo 1006) responde "o que aconteceu neste turno": a abertura
(`CASH_SESSION_OPENED`), cada venda com os itens, descontos e pagamentos dela (`SALE_*`/`PAYMENT_*`) e
o fechamento (`CASH_SESSION_CLOSED`). Leitura: todos os eventos do recorte têm o mesmo `cashSessionId`
e o mesmo operador; o `entityType` diz se o alvo é a sessão (`CASH_SESSION`) ou a venda (`SALE`), e o
`entityId` continua sendo o alvo do evento — a consulta por sessão não substitui o §3.1, é o recorte
do turno inteiro. Eventos sem caixa (login, usuários, estoque manual) não aparecem, porque a coluna é
nula neles.

## 4. Como consultar

```http
GET /api/v1/audit-events?entityType=&entityId=&actorUserId=&action=&cashSessionId=&from=&to=&sort=&page=&size=
Authorization: Bearer <token>        # exige audit.read
```

| Parâmetro | Semântica |
| --- | --- |
| `entityType` | Texto exato (`SALE`, `PRODUCT`, `CASH_SESSION`, `USER`, `AUTH_SESSION`, `CUSTOMER`, `ROLE`); em branco = sem filtro |
| `entityId` | UUID do alvo; o par `entityType`+`entityId` é a consulta "histórico desta entidade" (§7.3) |
| `actorUserId` | UUID de quem operou — "o que este operador fez" |
| `action` | Uma ação do catálogo; em branco = sem filtro |
| `cashSessionId` | Sessão de caixa (turno): é o filtro que traz a abertura, as vendas, os pagamentos e o fechamento de um caixa de uma vez |
| `from` | Início do período, **inclusivo**; ISO-8601 com offset (`2026-09-24T00:00:00Z`) |
| `to` | Fim do período, **exclusivo**; ISO-8601 com offset |
| `sort` | `occurredat` com `,asc`/`,desc` opcional; default `occurredat,desc` (mais recente primeiro). A ordem é `occurredAt` e o desempate por `id` na mesma direção |
| `page` | 0-based, default `0`; negativo → 400 `VALIDATION_ERROR` |
| `size` | Default `20`; menor que 1 → 400; acima de `100` é limitado (não recusado) |

Todos os filtros são opcionais e combináveis. `from`/`to` sem hora e offset (`2026-09-24`) → 400
`VALIDATION_ERROR`; o mesmo instante enviado em outro offset vale para qualquer fuso do cliente.
`sort` fora da whitelist → 400. A resposta é o envelope padrão de listagem (§9.1):

```json
{
  "items": [
    {
      "id": 1042,
      "occurredAt": "2026-09-24T18:31:07.123456Z",
      "storeId": "0198...",
      "actorUserId": "0198...",
      "actorUsername": "maria.gerente",
      "authSessionId": "0198...",
      "cashSessionId": "0198...",
      "cashRegisterId": "0198...",
      "action": "SALE_DISCOUNT_APPLIED",
      "entityType": "SALE",
      "entityId": "0198...",
      "source": "TUI",
      "requestId": "b1f0c3...",
      "reason": "cliente fidelidade",
      "details": { "type": "PERCENT", "value": 10, "discountAmount": 2.50, "total": 22.50 },
      "ip": "192.168.0.10"
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1
}
```

`occurredAt` vem em UTC; a conversão de fuso é da apresentação. Campo nulo é normal e esperado, não
erro: `cashSessionId` é nulo nas ações fora de um caixa (usuários, auth, estoque manual) e preenchido
nas de caixa, venda e pagamento (passo 1006); `actorUserId`, `actorUsername`, `authSessionId`,
`storeId` e `cashRegisterId` ficam nulos no login que falhou (rota anônima); e
`entityType`/`entityId`/`reason` vêm nulos com `details` = `{}` nas ações sem alvo ou sem motivo (ex.:
`ACCESS_DENIED`). Sempre leia o `action` antes de interpretar o resto.

## 5. Garantias e limitações

**Garantias**

- **Append-only no banco:** a role `minimarket_app` recebe apenas `select, insert` em `audit_events`
  (migration `V6`) — sem `update`/`delete`. Em dev/test a aplicação conecta como dona das tabelas
  (superusuário), então nenhum grant restringe essas conexões; a role existe para o deploy apontar o
  usuário da aplicação para ela, e o teste `AuditEventsMigrationTest` assume a role com
  `SET LOCAL ROLE` para provar a garantia.
- **Toda escrita de dinheiro/estoque é auditada na mesma transação** (§7.3): se a operação comita, o
  evento comita; se falha, o evento não fica.
- **Sem expurgo no MVP:** não há rotina de delete/retention. Particionamento por mês só quando o
  volume justificar (> 10 M linhas, §7.3); a limpeza de `idempotency_keys` (passo 1004) não toca a
  auditoria.
- **Leitura:** só pela consulta do §4, com `audit.read` (GERENTE/ADMIN). Não existe rota de escrita.

**Limitações conhecidas (registradas de propósito, não são bugs escondidos)**

- **`ACCESS_DENIED` é gravado em transação própria** (`REQUIRES_NEW`): o 403 já derrubou a transação
  da operação, então o evento não comita junto com nada. É a única ação sem operação de
  negócio correspondente — e a falha ao gravá-la ainda derruba a requisição (§7.1).
- **Replay de idempotência não audita de novo:** a segunda chamada com a mesma `Idempotency-Key`
  devolve a resposta armazenada e não gera evento.
- **`cash_session_id` só é preenchido nas ações que conhecem a sessão** (passo 1006): caixa
  (`CASH_SESSION_OPENED`/`CLOSED`/`CASH_SUPPLY`/`CASH_WITHDRAWAL`), vendas e pagamentos. Usuários,
  auth e estoque manual gravam a coluna nula de propósito — a sessão não é consultada por requisição
  para "adivinhar" o turno. Para o rastro fora do turno, use `actorUserId`, `entityType`/`entityId` ou
  o período.
- **A troca da própria senha revoga as demais sessões sem `SESSION_REVOKED`:** o evento
  `PASSWORD_CHANGED` grava a troca do usuário da sessão, mas o corte em massa do
  `ChangeOwnPasswordUseCase` não publica o evento que gera `SESSION_REVOKED` — o rastro das sessões
  derrubadas é `auth_sessions.revoked_reason = PASSWORD_CHANGED`, não uma linha do log.

## 6. Manutenção

Este catálogo é conferido contra o código — quem adicionar uma ação nova precisa atualizar este
arquivo. O comando de varredura (raiz do repositório, PowerShell):

```powershell
# todas as emissões de auditoria no código da aplicação
Get-ChildItem -Recurse -Path backend\src\main\java -Filter *.java |
  Select-String -Pattern 'auditRecorder\.record\(' | Select-Object Path, LineNumber

# as ações efetivamente emitidas (valores dos constantes resolvidos no arquivo)
$emitted = @(); Get-ChildItem -Recurse -Path backend\src\main\java -Filter *.java | ForEach-Object {
  $text = Get-Content -Raw $_.FullName
  $consts = @{}; [regex]::Matches($text, 'String\s+(\w+)\s*=\s*"([^"]+)"\s*;') |
    ForEach-Object { $consts[$_.Groups[1].Value] = $_.Groups[2].Value }
  foreach ($m in [regex]::Matches($text, 'auditRecorder\.record\(\s*([A-Za-z0-9_]+)')) {
    if ($consts.ContainsKey($m.Groups[1].Value)) { $emitted += $consts[$m.Groups[1].Value] }
  }
  foreach ($m in [regex]::Matches($text, 'recordLoginFailure\(\s*([A-Za-z0-9_]+)')) {
    if ($consts.ContainsKey($m.Groups[1].Value)) { $emitted += $consts[$m.Groups[1].Value] }
  }
}
$emitted | Sort-Object -Unique

# sentido inverso: constantes de ação declaradas no código (todas devem ter emissão)
Get-ChildItem -Recurse -Path backend\src\main\java -Filter *.java |
  Select-String -Pattern 'String\s+\w*ACTION\s*=\s*"' | Select-Object Path, LineNumber, Line
```

A conferência é nos **dois sentidos**:

1. **Emitidas → documentadas:** cada valor do `Sort-Object -Unique` do primeiro comando tem uma linha
   neste documento (nenhuma ação emitida fica de fora).
2. **Documentadas → emitidas:** cada constante `*_ACTION` do segundo comando aparece em pelo menos
   uma chamada de gravação (nenhuma linha deste documento descreve ação que o código não emite).
   Variável usada como ação (`LoginUseCase`) e emissores que não chamam o `record` diretamente
   (`AccessDeniedAuditRecorder`, chamado pelo observer) entram na conta manualmente.

**Conferência de 2026-09-24 (passos 1005/1006):** 39 chamadas de `auditRecorder.record(` e 39 valores
de ação distintos — `LOGIN_FAILED`/`LOGIN_LOCKED` saem da mesma chamada de `LoginUseCase`,
`SESSION_REVOKED` tem dois emissores (`RevokeSessionUseCase` e `UserAccessChangedObserver`) e
`PASSWORD_CHANGED` (passo 1006) é a ação nova da troca de senha. As 39 ações estão documentadas na §2,
e nenhuma das 39 constantes `*_ACTION` do código ficou sem emissão (0 declaradas-sem-emissão, 0
emitidas-sem-documentação). O único ponto de gravação é o `AuditRecorder` → `AuditEventStore.insert`
(`AuditEventRepository`): não há INSERT em `audit_events` fora desse caminho.
