package com.minimarket.cash.application;

import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Detalhe da sessão de caixa pelo id (passo 612), aberta ou fechada: é o que a retaguarda mostra
 * depois do fechamento. Leitura pura, sem {@code @Transactional}: não grava nada e a consulta é ida
 * só ao banco, como no {@code GetCurrentCashSessionUseCase} (passo 608).
 *
 * <p>Id desconhecido é 404 {@code CASH_SESSION_NOT_FOUND}: o código específico deixa o cliente
 * distinguir sessão inexistente de caixa sem sessão aberta ({@code CASH_SESSION_NOT_OPEN}). Os
 * campos de fechamento vêm nulos enquanto a sessão está aberta — quem os preenche é o fechamento
 * (passo 611).
 */
@ApplicationScoped
public class GetCashSessionUseCase {

  @Inject CashSessionStore cashSessionStore;

  public CashSessionSummary execute(UUID sessionId) {
    return cashSessionStore
        .findById(sessionId)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.CASH_SESSION_NOT_FOUND,
                    "sessão de caixa %s não encontrada".formatted(sessionId)));
  }
}
