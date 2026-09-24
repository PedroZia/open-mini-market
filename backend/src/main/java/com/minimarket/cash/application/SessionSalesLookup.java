package com.minimarket.cash.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Porta invertida do caixa para as vendas (passo 909): {@code cash} não pode depender de {@code
 * sales} — o {@code sales} já depende do {@code cash} e o grafo de módulos é acíclico (§2.2) —,
 * então o fechamento e o resumo perguntam pelas vendas da sessão por esta interface, implementada
 * em {@code sales.infrastructure}, que é quem conhece o banco da venda e o enum da forma de
 * pagamento.
 *
 * <p>Os dois métodos são leitura: não abrem transação nem gravam nada, como as demais consultas do
 * módulo.
 */
public interface SessionSalesLookup {

  /**
   * Existe venda {@code OPEN} na sessão de caixa? É o que o fechamento consulta para recusar fechar
   * com venda em andamento (409 {@code SESSION_HAS_OPEN_SALES}).
   */
  boolean existsOpenByCashSession(UUID cashSessionId);

  /**
   * Σ dos pagamentos {@code APPROVED} das vendas da sessão, por forma de pagamento. Venda {@code
   * CANCELLED} fica fora: o pagamento dela é matéria do estorno (Fase 13), não do que a gaveta
   * recebeu. O mapa vem <em>completo</em>: as cinco formas do enum, na ordem do enum,
   * zero-preenchidas quando não há pagamento naquela forma — só o adaptador conhece o enum, então é
   * ele que garante o shape estável que o resumo devolve.
   */
  Map<String, BigDecimal> sumApprovedPaymentsByMethod(UUID cashSessionId);
}
