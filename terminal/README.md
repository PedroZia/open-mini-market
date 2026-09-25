# Terminal (TUI) — PDV

A TUI em [Ink](https://github.com/vadimdemedes/ink) é o que o operador do caixa usa: login, abertura do
caixa, bipe dos itens, desconto, pagamento, sangria/suprimento, fechamento e troca de operador. Ela **não
calcula nada** — envia intenções e exibe o que o servidor respondeu (BR-12); o contrato HTTP vem do
[`@minimarket/api-client`](../packages/api-client/README.md).

O caminho completo do ambiente (banco + API + TUI) está no [README da raiz](../README.md); este guia é o
do computador do caixa.

## Requisitos

- **Node.js 22 ou superior** (o `npm` vem junto). Confira com `node --version`.
- Uma **janela de terminal de verdade** (cmd, PowerShell ou Windows Terminal) com pelo menos 80×24. A TUI
  usa o modo cru do teclado: rodando com a saída redirecionada para arquivo/pipe ela aborta com
  `Raw mode is not supported...`.
- A **API no ar** (nesta máquina ou na rede da loja) — veja abaixo como subir localmente.
- **Docker** apenas se for subir banco/API nesta máquina.

## Instalação

No diretório raiz do repositório (o `npm install` instala os workspaces, inclusive `terminal` e
`packages/api-client`):

```bash
npm install
```

## Configurando a API: `MINIMARKET_API_URL`

A TUI lê a URL da API da variável de ambiente **`MINIMARKET_API_URL`**. Sem a variável, usa o default de
desenvolvimento **`http://localhost:8081`** (a porta em que o backend de dev sobe neste repositório).

| Onde rodar | Como apontar para outro servidor |
| --- | --- |
| PowerShell (só nesta janela) | `$env:MINIMARKET_API_URL='http://192.168.0.10:8081'` |
| cmd (só nesta janela) | `set MINIMARKET_API_URL=http://192.168.0.10:8081` |
| Windows (permanente, vale para novos terminais) | `setx MINIMARKET_API_URL "http://192.168.0.10:8081"` |
| Atalho do PDV | `pdv.cmd http://192.168.0.10:8081` (ou edite a URL dentro do `.cmd`) |

No computador do caixa aponte para o **servidor da loja** (IP fixo), não para `localhost` — a API roda
em outra máquina. Depois de um `setx`, feche e abra o terminal (ou o atalho) de novo.

## Subindo a API localmente (dev)

Com Docker, banco + API em containers (perfil de produção; a porta publicada é a `APP_PORT` do `.env`):

```bash
copy .env.example .env     # no .env.example a APP_PORT já é 8081
docker compose up -d --build
```

Ou banco no container e API em dev mode:

```bash
docker compose up -d postgres
cd backend && .\mvnw.cmd quarkus:dev "-Dquarkus.http.port=8081"   # Windows; ./mvnw no Linux/macOS
```

Confira com `curl http://localhost:8081/q/health` (deve responder `UP`). O login inicial é `admin` /
`admin123` (`ADMIN_INITIAL_PASSWORD`), com **troca de senha obrigatória** no primeiro acesso.

> Se subir o `quarkus:dev` **sem** a flag, a API fica na porta padrão 8080: rode a TUI com
> `MINIMARKET_API_URL=http://localhost:8080`.

## Rodando o PDV

Da raiz do repositório:

```bash
npm start                  # roda o start do workspace @minimarket/terminal
```

ou direto nesta pasta:

```bash
npm start                  # tsx src/index.tsx
```

`npm run dev` roda o mesmo comando (nome mantido do passo 1101). A TUI sobe na tela de entrada do
operador; para sair, use o fluxo da tela (`F10` fecha o caixa e faz logout) e depois feche a janela do
terminal — ou `Ctrl+C`.

## Atalho no Windows (abrir com dois cliques)

O [`pdv.cmd`](./pdv.cmd) é o lançador: fixa `MINIMARKET_API_URL` se a variável não existir, aceita a URL
da API como primeiro argumento, entra na pasta do terminal e chama o `npm start` — se o PDV terminar com
erro, a janela fica aberta para o operador ler a mensagem.

Para criar o atalho na área de trabalho:

1. Abra a pasta `terminal` no Explorer.
2. Clique com o botão direito em **`pdv.cmd`** → **Enviar para** → **Área de trabalho (criar atalho)**.
3. (Opcional) Renomeie para "PDV minimercado" e troque o ícone em **Propriedades** → **Alterar ícone**.
4. Dois cliques no atalho abrem o PDV numa janela de console. O script entra na pasta dele mesmo
   (`cd /d "%~dp0"`), então o atalho funciona independente do "Iniciar em".
5. Para apontar para o servidor da loja, clique com o botão direito no atalho → **Propriedades** →
   acrescente a URL no fim do **Destino** (`...\pdv.cmd http://192.168.0.10:8081`) ou defina
   `MINIMARKET_API_URL` com o `setx` acima.

## Problemas comuns

| Sintoma | Causa provável |
| --- | --- |
| `Raw mode is not supported on the current process.stdin...` | a TUI está rodando sem terminal interativo (pipe, redirecionamento ou CI). Rode numa janela de terminal real. |
| Tela de erro de conexão / nada carrega | API fora do ar ou `MINIMARKET_API_URL` errada. Confira com `curl http://localhost:8081/q/health`. |
| `npm` não reconhecido | Node.js não instalado ou fora do `PATH` — instale o Node 22+ e abra o terminal de novo. |
| Erro de sintaxe/`Unsupported Node.js version` | Node anterior ao 22: atualize. |

## Desenvolvimento

```bash
npm test          # vitest
npx tsc --noEmit  # typecheck
npm run lint      # eslint
```
