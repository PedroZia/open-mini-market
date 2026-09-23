# AGENTS.md — Regras de trabalho neste repositório

Este repositório implementa um **PDV para minimercado** (Quarkus + PostgreSQL no backend, TUI em Ink e
React Web como clientes). O plano está em [`docs/plano-tecnico.md`](docs/plano-tecnico.md) e a execução
passo a passo em [`docs/roadmap.md`](docs/roadmap.md).

## Como trabalhar (regra inegociável)

1. **Um passo por vez, na ordem do roadmap.** Encontre o primeiro passo com checkbox vazio, execute apenas ele.
2. **Não antecipe passos futuros.** Não crie abstração, endpoint, campo ou tabela "para depois" sem que
   exista um passo pedindo aquilo.
3. **Ciclo obrigatório:** implementar → testar → validar (`mvn verify` ou `npm test` + `tsc --noEmit`) →
   commit → marcar o checkbox do passo no roadmap (dentro do mesmo commit) → seguir.
4. **Se o passo parecer grande, divida antes de codar.** Registre a divisão no roadmap (subpassos com
   letra: `413a`, `413b`) e execute um de cada vez. Um passo não deve passar de ~300 linhas de mudança
   nem de uma sessão curta de trabalho.
5. **Nunca deixe o build vermelho.** Se algo quebrou e você não consegue resolver no mesmo passo, reverta
   para o último estado verde em vez de commitar meio caminho.
6. **Ao final de cada módulo (fase), pare e reporte:** o que foi feito, quais testes rodaram, critérios de
   aceite atendidos, riscos/dúvidas e o próximo passo sugerido. Não emende módulos sem esse checkpoint.

## Definition of Done de qualquer passo

- [ ] Escopo exatamente o do passo (nem mais, nem menos).
- [ ] Testes no nível adequado: regra pura → unitário; persistência → integração com PostgreSQL real;
      endpoint → teste de API com RestAssured.
- [ ] `mvn verify` verde (backend) / `npm test` + `tsc --noEmit` verdes (TUI/Web).
- [ ] Critério de aceite descrito no passo conferido e citado na mensagem de commit ou no PR.
- [ ] Commit no padrão `tipo(módulo): descrição` em pt-BR, ex.: `feat(cash): implementa sangria`.
- [ ] Checkbox do passo atualizado em `docs/roadmap.md`.

## Convenções

| Item | Regra |
| --- | --- |
| Idioma | código e identificadores em **inglês**; docs, commits e `@DisplayName` em **pt-BR** |
| Java | Java 25, Quarkus 3.33 LTS, Maven; sem Lombok |
| Persistência | Hibernate ORM/JPA explícito, sem Panache; **proibido** `hbm2ddl.auto`; schema só por Flyway |
| Migrations | `V{n}__descricao.sql`; migration aplicada **nunca** é editada — corrija com uma nova |
| Testes | PostgreSQL real (Dev Services/Testcontainers); **proibido** H2 e mock de banco |
| IDs | UUIDv7 gerado na aplicação (`uuid-creator`); exceção: `audit_events` usa `bigint identity` |
| Dinheiro | `numeric(14,2)`, `BigDecimal`, arredondamento `HALF_UP`; quantidade `numeric(14,3)` |
| Datas | `timestamptz` em UTC; conversão de fuso só na apresentação |
| Tabelas/colunas | `snake_case`, plural, sem palavras reservadas |

## Arquitetura em uma tela

```text
backend/src/main/java/com/minimarket/
  shared/  auth/  users/  catalog/  inventory/  cash/  sales/  customers/  audit/  reports/
      cada módulo:  api/ → application/ → domain/  ;  infrastructure/ implementa portas
terminal/   TUI em TypeScript: core/ (lógica pura) + api/ (client gerado) + ui/ (Ink)
web/        React + Vite: features/<feature>/ (api, hooks, pages, components)
packages/api-client/  tipos e client gerados do OpenAPI (compartilhado)
```

Regras de fronteira (há testes ArchUnit — violar quebra o build):

1. `api` **não** contém regra de negócio: valida forma, delega ao caso de uso, mapeia resposta.
2. `domain` é Java puro: sem JPA, Quarkus, Jackson ou HTTP.
3. Ninguém fora de `infrastructure` importa JPA. Entidade JPA **nunca** é serializada em JSON.
4. Um módulo não acessa `infrastructure` de outro; comunicação via `application` ou evento.
5. Sem dependência circular entre módulos. `audit` é folha.
6. Um caso de uso = uma transação (`@Transactional` em `application`), nunca no repositório.

## Regras de negócio que não podem ser violadas

Estão em §4.4 do plano (`BR-01` a `BR-14`). As mais fáceis de esquecer:

- **O servidor sempre recalcula** totais, desconto, troco e saldo. Nunca aceite valor calculado do cliente.
- **Preço é snapshot** no item da venda; alterar preço do produto não muda venda em andamento/concluída.
- **Venda concluída é imutável**; correção é estorno com movimento compensatório e auditoria.
- **Saldo de estoque só muda via `StockService`**, sempre gravando linha no ledger com `balance_after`.
- **Toda operação de dinheiro/estoque é auditada na mesma transação** e é idempotente (`Idempotency-Key`).
- **Código de barras é interpretado no servidor** (GTIN, código interno ou etiqueta de balança); o cliente
  envia a string bruta e nunca calcula quantidade de etiqueta.
- **Nada da Fase 14 (fiscal) antes dela**: sem colunas fiscais, certificado, CSC ou motor de impostos.

## Comandos úteis

```bash
# backend
docker compose up -d postgres          # banco de dev
cd backend && mvn quarkus:dev          # API em modo dev (http://localhost:8080)
cd backend && mvn verify               # build + testes (obrigatório antes do commit)

# TUI (a partir da fase 11)
cd terminal && npm test && npx tsc --noEmit && npm start

# web (a partir da fase 12)
cd web && npm test && npx tsc --noEmit && npm run dev
```

## O que NÃO fazer

- Não adicionar biblioteca sem justificar no commit o que ela resolve que o padrão não resolve.
- Não criar camada de serviço genérica (`BaseService`, `GenericRepository`) — cada caso de uso é explícito.
- Não usar `Optional` como parâmetro, nem retornar entidade JPA em DTO.
- Não silenciar exceção de auditoria; falha ao auditar derruba a transação (é intencional).
- Não introduzir cache, fila, broker, Redis, native image ou microsserviço sem passo no roadmap.
- Não alterar migration já aplicada nem o formato de `audit_events`.
- Não commitar segredo, `.env` ou dump de banco.

## Quando estiver em dúvida

1. Consulte `docs/plano-tecnico.md` (§4 regras, §5 banco, §8 concorrência, §9 API, §14 testes).
2. Se a dúvida for de escopo, **não decida sozinho**: registre a pergunta no final da resposta e proponha
   a alternativa mais simples, seguindo o princípio de menor complexidade.
3. Se o plano e o roadmap conflitarem, o plano vence em arquitetura e o roadmap em sequência; registre o
   conflito para revisão.
4. Antes de escrever API de biblioteca (Quarkus, Hibernate, Flyway, Ink, TanStack Query...), confirme a
   assinatura na fonte oficial listada em [`docs/referencias.md`](docs/referencias.md). Não invente
   método, anotação, parâmetro ou propriedade de configuração.
