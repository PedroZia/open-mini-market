package com.minimarket.cash.application;

import com.minimarket.cash.domain.CashMovementType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência da sessão de caixa e dos seus movimentos (passo 604); o adaptador JPA fica
 * em {@code cash.infrastructure}. Só tipos de aplicação atravessam: entidade JPA nunca chega aqui.
 */
public interface CashSessionStore {

  /** Insere a sessão (nasce {@code OPEN}) e devolve o id gerado (UUIDv7) pelo adaptador. */
  UUID insert(NewCashSession session);

  /**
   * Sessão aberta do caixa informado — é o que o caso de uso de abertura consulta para recusar um
   * caixa já aberto (passo 606) e o que o status do caixa mostra (passo 602). Vazio quando o caixa
   * não tem sessão aberta.
   */
  Optional<CashSessionSummary> findOpenByRegister(UUID cashRegisterId);

  /**
   * Sessão pelo id, aberta ou fechada: o histórico e o resumo (passos 608 e 612) leem sessão
   * fechada. Vazio para id desconhecido.
   */
  Optional<CashSessionSummary> findById(UUID id);

  /** Insere o movimento do ledger e devolve o id gerado (UUIDv7) pelo adaptador. */
  UUID insertMovement(NewCashMovement movement);

  /**
   * Soma dos valores assinados dos movimentos da sessão, por tipo: o saldo esperado do caixa (passo
   * 605) sai daqui. Tipo sem movimento fica fora do mapa.
   */
  Map<CashMovementType, BigDecimal> sumByType(UUID cashSessionId);

  /**
   * Sessão pelo id com lock pessimista de escrita na linha ({@code SELECT ... FOR UPDATE}): a
   * transação que chama segura o lock até o fim e outra transação que tente a mesma linha espera a
   * primeira soltar. É o que permite fechar o caixa sem corrida (passo 611). Vazio para id
   * desconhecido.
   */
  Optional<CashSessionSummary> lockById(UUID id);
}
