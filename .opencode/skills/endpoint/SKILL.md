---
name: Endpoint REST do PDV
description: Implementa um endpoint REST do PDV na ordem correta — domínio, caso de uso transacional, adaptador e API — com permissão, auditoria na mesma transação, idempotência quando move dinheiro/estoque e teste RestAssured. Use quando o passo pedir endpoint, rota, API, recurso REST ou controller.
---

# Endpoint REST

## Ordem de construção (não pule etapas)

1. **Domínio** (`domain/`): regra pura, sem JPA/Quarkus/Jackson/HTTP. Teste unitário aqui.
2. **Caso de uso** (`application/`): `@Transactional`, orquestra domínio + portas, chama o
   `AuditRecorder`. **Uma operação = uma transação.**
3. **Porta + adaptador** (`infrastructure/`): JPA/repositório implementando a porta declarada no
   `application`.
4. **API** (`api/`): valida forma (Bean Validation), delega ao caso de uso, mapeia resposta para DTO.
   **Zero regra de negócio.**

## Checklist do endpoint

- [ ] Caminho em `/api/v1/...`, recurso no plural, JSON em `camelCase`.
- [ ] Permissão do projeto na rota (fase 4+). Endpoint novo sem permissão é bug — há teste que denuncia.
- [ ] Erros no padrão `problem+json` com `code` estável: `400` forma · `401` sem sessão · `403` sem
      permissão · `404` inexistente · `409` conflito de estado/concorrência/idempotência · `422` regra de
      negócio · `429` rate limit.
- [ ] Dinheiro/estoque: `Idempotency-Key` obrigatório **e** auditoria na mesma transação.
- [ ] Totais, descontos, troco e saldos são **recalculados no servidor** (BR-12). Nada vindo do cliente é
      aceito como verdade.
- [ ] Preço é snapshot no item da venda (BR-01).
- [ ] Listagem com paginação (`page`/`size`, `size` máximo 100) e filtros.
- [ ] Entidade JPA **nunca** serializada: mapeie explicitamente para DTO.
- [ ] Edição concorrente usa `version` + `If-Match` → `409` em conflito.
- [ ] Código de barras: o cliente envia a string bruta; o servidor resolve (BR-14).
- [ ] Mensagem de erro não vaza detalhe interno (stack, SQL, nome de tabela).

## Teste (RestAssured, `@QuarkusTest`)

Cubra: caminho feliz, cada código de erro aplicável, autorização por permissão (fase 4+) e idempotência
(mesma chave → mesma resposta e um único efeito; corpo diferente → `409`).

## Fechamento

- `cd backend && ./mvnw verify` (Windows: `cd backend; .\mvnw.cmd verify`).
- Se o contrato mudou, o OpenAPI é a fonte dos clients TS — mantenha-o correto.
