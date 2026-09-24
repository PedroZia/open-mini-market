package com.minimarket.sales.application;

import com.minimarket.sales.domain.SaleStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Entrada do {@link ListSalesUseCase} (passo 812): os filtros do histórico (§9.3) e a paginação.
 * Todos os filtros são opcionais — nulo é "sem filtro" — e a consulta é da loja inteira, porque a
 * rota exige {@code report.read} (§4.5: o OPERADOR não tem a permissão de relatório).
 *
 * <p>Período com {@code from} inclusivo e {@code to} exclusivo sobre {@code created_at} (§9.1).
 * {@code page} é 0-based e {@code size} tem o teto de 100; a validação é do caso de uso, não da
 * API.
 *
 * @param from início do período, inclusivo; nulo é sem limite inferior
 * @param to fim do período, exclusivo; nulo é sem limite superior
 * @param status status da venda; nulo é qualquer um
 * @param cashSessionId sessão de caixa que abriu a venda; nulo é qualquer uma
 * @param operatorUserId operador que abriu a venda; nulo é qualquer um
 * @param page página 0-based
 * @param size tamanho da página, no máximo 100
 */
public record ListSalesQuery(
    Instant from,
    Instant to,
    SaleStatus status,
    UUID cashSessionId,
    UUID operatorUserId,
    int page,
    int size) {}
