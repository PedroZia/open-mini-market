package com.minimarket.shared.domain;

/**
 * Campo embutido na etiqueta de balança (BR-14, passo 1104b1): o que a balança imprime no código —
 * o peso do produto ({@code WEIGHT}) ou o preço total ({@code PRICE}). É configuração da loja
 * ({@code stores.scale_embedded_field}) e quem interpreta o valor é o servidor, nunca o cliente. Os
 * nomes são os aceitos pelo check constraint da coluna.
 */
public enum ScaleEmbeddedField {
  WEIGHT,
  PRICE
}
