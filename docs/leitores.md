# Leitores de código de barras

Guia prático para configurar o leitor (bipador) do PDV e resolver o "o leitor não funciona" sem
chamar o suporte. Vale para qualquer leitor de mão em **modo teclado** (USB HID, "keyboard wedge"),
que é o que as famílias usadas na loja — Toledo, Filizola, Prix, Urano — usam por padrão.

> O caminho do menu muda de modelo para modelo. Onde este guia diz "opção X", confira no manual do
> equipamento (ou no código de barras de configuração do fabricante) o nome exato da opção.

## Como o PDV lê o código

- **O leitor é um teclado.** Não existe driver, porta serial nem COM: o PDV só enxerga as teclas.
- O PDV trata a **rajada de caracteres com intervalo < 50 ms terminada em `ENTER` ou `TAB`** como uma
  leitura; digitação humana é ignorada. Sem o sufixo, a leitura nunca fecha — e nada aparece na tela.
- O código vai **bruto** ao servidor (BR-14): é o servidor que decide se o código é GTIN, código
  interno (PLU) ou etiqueta de balança, conforme a configuração da loja. O terminal não interpreta
  nada — por isso o autoteste (`F11`) mostra o código exatamente como chegou.
- O PDV mantém o texto como veio (espaços inclusive); o servidor normaliza antes de procurar o
  produto (trim e sem espaços internos).

## Checklist de configuração

| O que | Como deve ficar | Por quê |
| --- | --- | --- |
| Sufixo (suffix / terminador) | `ENTER` (CR) ou `TAB` | é o que fecha a leitura; sem sufixo o PDV acumula teclas e nada acontece |
| Prefixo / AIM ID / "transmit identifier" | **desligado** | prefixo vira caractere no código e derruba a busca do produto |
| Corte, substituição ou reordenação de dígitos | **desligado** | o servidor precisa do código inteiro; corte no leitor esconde informação |
| Simbologias | EAN-13 ligada (e UPC-A, se a loja usa); Code 128/39, ITF e QR **só** se a loja usar | simbologia desligada = código não lido; simbologia a mais = leitura cruzada e código errado |
| Layout de teclado (keyboard layout / country code) | **US** | ABNT2 troca `'`, `"`, `/` e `;`: códigos só de dígitos saem iguais, mas Code 128 com esses caracteres sai diferente |
| Conferência do dígito verificador (DV / check digit) | como veio de fábrica | o sistema **não** confere o DV da etiqueta de balança; se a leitura falha, o motivo é outro |
| Modo de envio | teclado USB (HID) | serial, virtual COM, HID-POS e "leitor + software do fabricante" não são lidos pelo PDV |
| Tempo entre caracteres (inter-character delay) | o menor possível (zero) | rajada espaçada (>= 50 ms entre caracteres) é descartada como digitação humana |
| Beep e iluminação | como o operador preferir | não afetam a leitura |

Nas famílias Toledo, Filizola, Prix e Urano, o caminho curto costuma ser aplicar o **código de
barras de configuração** do manual com o perfil "USB HID teclado + ENTER" — confirme no manual do
modelo (a página de configuração rápida traz os códigos um por um: sufixo, simbologias, layout).

**O layout do sistema operacional também conta:** se o Windows está em ABNT2 e o leitor em US, os
dígitos saem iguais, mas os símbolos não. O padrão do PDV é US nas duas pontas.

## Etiqueta de balança (produto pesável)

O que o servidor entende tem de bater com o que a balança imprime. O formato é **premissa do
parser** — não há dígito verificador conferido nem leitura do manual do equipamento:

- **13 dígitos** no total (EAN-13): `prefixo` + `código interno` + `valor embutido`.
- O `prefixo` (`internal_barcode_prefix`) abre o namespace da etiqueta: código de 13 dígitos que
  começa com ele **é** tentativa de etiqueta — se não couber no formato configurado, o PDV responde
  `422 INVALID_INTERNAL_BARCODE` em vez de "produto não encontrado".
- O `código interno` tem **exatamente** `internal_code_length` dígitos, logo depois do prefixo.
- O resto é o `valor embutido`: um inteiro cru escalado por `scale_embedded_decimals` (com 3 casas,
  `0001234` = 1,234 kg). `scale_embedded_field` diz o que ele significa: `WEIGHT` é peso em kg (vai
  direto para a quantidade do item) e `PRICE` é o total em reais (o servidor divide pelo preço do
  produto para achar o peso).
- **O dígito verificador do EAN-13 não é conferido** pelo sistema. Se as balanças da loja o
  imprimirem no meio do código, o formato acima não descreve a etiqueta — confira com uma etiqueta
  real da loja antes de fechar a configuração (e ajuste a balança ou este guia).
- Valor embutido zero ou negativo é recusado: `422 INVALID_INTERNAL_BARCODE`.
- O ajuste é dos dois lados: ou a balança imprime no formato da configuração da loja, ou a
  configuração da loja passa a descrever o formato da balança.

Com os defaults da MATRIZ, a etiqueta é:

```text
2 00042 0001234
|   |      `---- valor embutido: 7 dígitos = 1,234 (3 casas decimais)
|   `----------- código interno (PLU): 5 dígitos
`--------------- prefixo da etiqueta: 2
```

### Como configurar a loja (SQL)

**Não existe endpoint nem permissão para configurar a balança**: os parâmetros são colunas da tabela
`stores` e mudam por SQL, no banco de dev/produção (é configuração de instalação, não operação de
PDV). Com os defaults da MATRIZ:

```sql
update stores
   set internal_barcode_prefix  = '2',      -- prefixo da etiqueta (namespace do parser)
       internal_code_length     = 5,        -- dígitos do código interno (PLU), logo após o prefixo
       scale_embedded_field     = 'WEIGHT', -- WEIGHT = peso em kg; PRICE = total em reais
       scale_embedded_decimals  = 3         -- casas decimais do valor embutido
 where code = 'MATRIZ';
```

`internal_barcode_prefix` e `internal_code_length` descrevem a etiqueta; mudá-los depois de os
produtos estarem cadastrados exige reconferir os códigos internos já gravados (a etiqueta passa a ser
lida com o tamanho novo e os códigos antigos não casam mais).

### Como gravar o código interno no produto

O `internalCode` entra pelo cadastro (`POST /api/v1/products`) e pela edição
(`PUT /api/v1/products/{id}`, com `If-Match`) — os dois exigem a permissão `product.write`
(OPERADOR não tem). No `PUT` o campo nulo (ou ausente) **limpa** o código, como descrição e
quantidade mínima.

- Só **dígitos**: `trim`, sem espaços internos; vazio é produto sem código interno.
- O servidor completa com **zeros à esquerda** até `internal_code_length`: digitar `42` grava
  `00042`. É o que mantém o mesmo produto resolvendo pela etiqueta (`2` + `00042` + valor) e pelo
  código interno digitado no bipe (`42` ou `00042`).
- Mais dígitos que o configurado ou caractere não numérico → `400 VALIDATION_ERROR` apontando
  `internalCode` em `errors[]`.
- Código já usado por produto vivo → `409 INTERNAL_CODE_ALREADY_EXISTS` (desativar o produto libera
  o código, como acontece com o barcode).

## Autoteste do leitor (`F11`)

`F11` abre o autoteste. Bipe um produto de teste e a tela mostra:

- **última leitura** — o código exatamente como o leitor mandou, sem trim nem parse (BR-14);
- **intervalo entre caracteres** — o maior intervalo dentro da rajada e a duração total: é o que diz
  se o leitor está rápido o bastante (o limite do PDV é 50 ms entre caracteres);
- **interpretação (do servidor)** — o produto que o servidor encontrou (nome e preço), a quantidade
  sugerida quando é etiqueta de balança, ou o `code` do erro (`PRODUCT_NOT_FOUND`,
  `INVALID_INTERNAL_BARCODE`, ...);
- **instruções de configuração** — o mesmo checklist deste guia, resumido na tela.

Como ler o resultado:

| O que acontece | O que significa | O que fazer |
| --- | --- | --- |
| Nada muda na tela ao bipar | o leitor não manda sufixo, ou o modo de envio não é teclado | configure sufixo `ENTER`/`TAB` e teste no Bloco de Notas |
| O código aparece trocado ou cortado | prefixo/AIM ID, corte de dígitos ou layout de teclado | desligue prefixo e corte; leitor e Windows em US |
| Intervalo >= 50 ms entre caracteres | rajada lenta: o PDV descartou o começo e leu só o fim (ou nada) | zere o "inter-character delay" do leitor |
| Código certo, mas `404 PRODUCT_NOT_FOUND` | produto não cadastrado com esse código, inativo ou soft-deletado | confira o cadastro do produto (código exato, sem dígitos a mais ou a menos) |
| `422 INVALID_INTERNAL_BARCODE` | a balança imprimiu uma etiqueta fora do formato configurado | acerte o formato da balança ou a configuração da loja |
| `401 SESSION_EXPIRED` / `403 ACCESS_DENIED` no teste | sessão do terminal expirada ou operador sem permissão de leitura | refaça o login do operador |

## Quando o leitor não funciona

Checklist, em ordem:

1. **Fora do PDV:** abra o Bloco de Notas e bipe. Não apareceu nada? Cabo USB, modo de envio ou
   equipamento — troque a porta e o cabo antes de qualquer coisa.
2. Apareceu e **não pulou linha**? Falta o sufixo `ENTER`/`TAB`.
3. Apareceu cortado, com prefixo ou caractere trocado? Prefixo/AIM ID, corte de dígitos ou layout de
   teclado (checklist acima).
4. **No PDV:** abra o `F11` e bipe de novo. A leitura não aparece? Volte ao passo 2 (é o sufixo). A
   leitura aparece com intervalo alto? Passo do tempo entre caracteres.
5. Leitura certa e erro do servidor? Então o leitor está bem: é cadastro ou configuração de etiqueta
   (tabela acima).
6. Nada resolveu? Acione o suporte com **modelo do leitor**, o **código lido** (o `F11` mostra o
   código bruto, dá para copiar) e a **mensagem/`code`** que a tela apresentou.

## Para quem mexe no código

- `terminal/src/core/scanner.ts` — regras da rajada (timing, terminador, multiplicador `3*`).
- `terminal/src/ui/ReaderSelfTestScreen.tsx` — a tela do `F11`.
- `catalog/domain/ScaleLabel.java` e `catalog/application/BarcodeResolver.java` — a interpretação do
  código, que é sempre do servidor (BR-14).
- `catalog/application/InternalCodeNormalizer.java` — a regra do código interno (só dígitos, zeros à
  esquerda até `internal_code_length`), a mesma no cadastro e no bipe.
- `docs/plano-tecnico.md` §11.3 — UX de teclado e leitor.
