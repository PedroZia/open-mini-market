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

O que a balança imprime tem de bater com a configuração da loja (tabela `stores`, colunas
`internal_barcode_prefix`, `internal_code_length`, `scale_embedded_field` e
`scale_embedded_decimals`; no MVP valem os defaults da MATRIZ). Com os defaults, a etiqueta é:

```text
2 00042 0001234
|   |      `---- valor embutido: 7 dígitos = 1,234 (3 casas decimais)
|   `----------- código interno (PLU): 5 dígitos
`--------------- prefixo da etiqueta: 2
```

- **13 dígitos** no total (EAN-13): prefixo + código interno + valor embutido.
- `scale_embedded_field` decide o que o valor embutido significa: `WEIGHT` é peso em kg (vai direto
  para a quantidade do item) e `PRICE` é o total em reais (o servidor divide pelo preço do produto
  para achar o peso).
- O código interno gravado no produto (`products.internal_code`) tem de ser **exatamente** os dígitos
  que a balança imprime, com os zeros à esquerda: `00042`, não `42`.
- Valor embutido zero ou negativo é recusado: `422 INVALID_INTERNAL_BARCODE`.
- **O dígito verificador do EAN-13 não é conferido** pelo sistema: o formato é prefixo + PLU + valor,
  como acima. Se o leitor tiver conferência de DV ligada, tudo bem — mas não é o PDV que a faz.
- O ajuste é dos dois lados: ou a balança imprime no formato da configuração da loja, ou a
  configuração da loja passa a descrever o formato da balança.

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
- `docs/plano-tecnico.md` §11.3 — UX de teclado e leitor.
