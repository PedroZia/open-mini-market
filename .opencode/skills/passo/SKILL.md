---
name: Executar passo do roadmap
description: Executa um passo do roadmap do PDV de ponta a ponta — acha o primeiro checkbox vazio em docs/roadmap.md, implementa somente ele, roda o gate de testes, marca o checkbox e propõe o commit. Use quando o usuário disser "próximo passo", "faça o passo 413", "continue o roadmap", "implemente o 1104b" ou equivalente.
---

# Executar um passo do roadmap

As regras inegociáveis estão em `AGENTS.md` — releia antes de começar. Este skill é o procedimento; o
`AGENTS.md` é a lei.

## Procedimento

1. **Determine o passo**
   - Se o usuário informou número/letra (`413`, `1104b`), use-o.
   - Senão, ache o primeiro `- [ ]` em `docs/roadmap.md`, na ordem do arquivo.
   - Confira as dependências listadas no passo: se alguma ainda estiver `- [ ]`, **pare e avise** em vez de
     implementar.

2. **Leia o passo inteiro** (Objetivo, Depende, Implementar, Testes/aceite, Commit). O escopo é
   **exatamente** o descrito: nada de campo, endpoint, tabela ou abstração "para depois".

3. **Planeje antes de codar**: liste os arquivos que serão criados/alterados e onde cada teste vai morar.
   Se passar de ~300 linhas de mudança ou não couber em ~1 h, divida em subpassos (`413a`, `413b`) e
   **registre a divisão no roadmap antes de continuar**.

4. **Implemente** respeitando as fronteiras de `docs/plano-tecnico.md`:
   - `api` valida forma e delega; `application` tem o caso de uso com `@Transactional`; `domain` é Java
     puro (sem JPA/Quarkus/Jackson/HTTP); `infrastructure` implementa as portas.
   - Teste no nível adequado: regra pura → unitário; persistência → integração com PostgreSQL real;
     endpoint → RestAssured. **Nunca H2, nunca mock de banco, nunca entidade JPA em JSON.**

5. **Rode o gate** (do diretório correto):
   - Backend: `cd backend && ./mvnw verify` (Windows: `cd backend; .\mvnw.cmd verify`)
   - TUI: `cd terminal && npm test && npx tsc --noEmit`
   - Web: `cd web && npm test && npx tsc --noEmit`
   Se falhar, conserte. Nunca deixe o build vermelho.

6. **Feche o passo no mesmo commit:**
   - Marque `- [ ]` → `- [x]` no `docs/roadmap.md`.
   - Commit no padrão `tipo(módulo): descrição` em pt-BR. Cite o critério de aceite no corpo quando ele
     não for óbvio pelo título.
   - **Nada fora do escopo do passo** no commit.
   - Se a skill `ponytail-review` estiver disponível, rode-a no diff antes de commitar.

7. **Reporte**: o que mudou, quais testes rodaram, critério de aceite atendido e próximo passo sugerido.

## Proibido

- Antecipar passos futuros ou criar "estrutura para depois".
- Editar migration já aplicada (`V{n}__*.sql` existente).
- Silenciar exceção de auditoria (falha ao auditar derruba a transação — é intencional).
- Marcar o checkbox sem o gate verde.
