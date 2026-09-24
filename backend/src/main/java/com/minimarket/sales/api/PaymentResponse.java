package com.minimarket.sales.api;

import com.minimarket.sales.domain.PaymentMethod;
import com.minimarket.sales.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pagamento da venda como a API o devolve no detalhe (§9.3, passo 905): a forma, o valor pago, o
 * valor entregue e o troco — os dois últimos calculados pelo servidor (BR-12) —, o status e os
 * instantes. {@code tenderedAmount} e {@code changeAmount} são nulos/zero fora do dinheiro, porque
 * troco só existe em {@code CASH} (BR-05), e {@code cancelledAt} é nulo enquanto o pagamento está
 * aprovado (o cancelamento antes da conclusão é o {@code DELETE} da rota).
 *
 * <p>{@code createdByUserId} é quem registrou o pagamento — o ator da sessão, nunca o cliente
 * (BR-11) —, informação que a TUI usa para mostrar quem recebeu cada pagamento quando o caixa passa
 * de mão em mão. {@code externalRef}, {@code authorizationCode} e {@code cancelReason} são detalhe
 * do banco e não entram; a projeção {@code Payment} do domínio é mapeada para cá — entidade JPA
 * nunca vai a JSON.
 */
public record PaymentResponse(
    UUID id,
    PaymentMethod method,
    BigDecimal amount,
    BigDecimal tenderedAmount,
    BigDecimal changeAmount,
    PaymentStatus status,
    UUID createdByUserId,
    Instant createdAt,
    Instant cancelledAt) {}
