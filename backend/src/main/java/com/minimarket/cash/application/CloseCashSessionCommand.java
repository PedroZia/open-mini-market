package com.minimarket.cash.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada do {@link CloseCashSessionUseCase}: o caixa a fechar, o valor contado na conferência, as
 * observações do fechamento e quem fechou. As observações podem ser nulas — o operador nem sempre
 * tem o que anotar. O ator vem da API (passo 612), que o lê do {@code OperationContext}; o caso de
 * uso não o conhece.
 */
public record CloseCashSessionCommand(
    UUID cashRegisterId, BigDecimal countedAmount, String closingNotes, UUID closedByUserId) {}
