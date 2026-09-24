package com.minimarket.cash.application;

import com.minimarket.cash.domain.CashSessionStatus;
import java.util.UUID;

/**
 * Caixa com o status atual, como o caso de uso da listagem (passo 602) devolve: {@code status} é
 * {@code OPEN} quando há sessão aberta no caixa e {@code CLOSED} caso contrário; {@code
 * operatorName} é o nome de quem abriu a sessão aberta (nulo quando fechado).
 */
public record CashRegisterView(
    UUID id, String code, String name, CashSessionStatus status, String operatorName) {}
