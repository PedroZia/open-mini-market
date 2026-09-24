package com.minimarket.cash.api;

import com.minimarket.cash.domain.CashSessionStatus;
import java.util.UUID;

/**
 * Caixa como a API devolve (§9.3, passo 602): só os campos do contrato — {@code storeId} e {@code
 * active} são detalhe do banco (a lista já traz só os ativos). {@code status} é o da sessão atual
 * ({@code OPEN}/{@code CLOSED}) e {@code operatorName} é o nome de quem abriu a sessão aberta, nulo
 * quando o caixa está fechado. A projeção {@code CashRegisterView} de {@code application} é mapeada
 * para cá; entidade JPA nunca vai a JSON.
 */
public record CashRegisterResponse(
    UUID id, String code, String name, CashSessionStatus status, String operatorName) {}
