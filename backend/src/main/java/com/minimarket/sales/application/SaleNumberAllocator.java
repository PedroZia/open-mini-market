package com.minimarket.sales.application;

import java.util.UUID;

/**
 * Porta de alocação do número sequencial da venda (passo 804): cada loja tem a própria série, que
 * vive em {@code document_sequences} — a mesma tabela que o §5.3 reserva para a NFC-e da Fase 14,
 * onde o {@code doc_type} separa as séries. O adaptador fica em {@code sales.infrastructure}.
 *
 * <p>Sem {@code @Transactional} de propósito: a alocação participa da transação do caso de uso
 * (§2.2, regra 6), e é isso que faz o número voltar para a série quando a venda falha depois de
 * abrir — sem buraco visível por loja. A contrapartida é que quem chama precisa estar numa
 * transação: fora dela o banco não tem o que desfazer.
 */
public interface SaleNumberAllocator {

  /**
   * Próximo número da loja, sem buraco: a linha da série nasce na primeira alocação (número 1) e o
   * lock de linha do banco serializa as chamadas concorrentes (§8), então duas vendas simultâneas
   * nunca recebem o mesmo número.
   */
  long nextNumber(UUID storeId);
}
