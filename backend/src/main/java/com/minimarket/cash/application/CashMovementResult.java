package com.minimarket.cash.application;

import com.minimarket.cash.domain.CashMovementType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resultado de um movimento de dinheiro gravado na sessão de caixa: a sangria (passo 609) e o
 * suprimento (passo 610) devolvem o mesmo record — é o que a API responde e a TUI usa para
 * confirmar a operação ao operador.
 *
 * <p>{@code amount} é o valor <em>informado</em> pelo operador, positivo — quem guarda o sinal do
 * tipo é o ledger ({@code cash_movements}: sangria negativa, suprimento positivo); manter o valor
 * digitado é o que deixa o alerta legível para quem opera o caixa. {@code expectedBefore} e {@code
 * expectedAfter} são o saldo esperado da regra única do {@code CashSessionAmounts} antes e depois
 * do movimento, na escala 2 do projeto.
 *
 * <p>{@code aboveExpected} é <em>alerta, nunca bloqueio</em>: sangria maior que o saldo esperado é
 * registrada como qualquer outra e devolve {@code true} para o cliente avisar o operador — o
 * dinheiro físico pode ter entrado por um caminho que o sistema ainda não viu.
 */
public record CashMovementResult(
    UUID sessionId,
    CashMovementType type,
    BigDecimal amount,
    String reason,
    BigDecimal expectedBefore,
    BigDecimal expectedAfter,
    boolean aboveExpected) {}
