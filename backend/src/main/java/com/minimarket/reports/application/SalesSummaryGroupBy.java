package com.minimarket.reports.application;

import com.minimarket.shared.domain.FieldValidationException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Dimensões de agrupamento do resumo de vendas (§9.3, passo 1212a): é o {@code groupBy} da
 * consulta, que o cliente manda em minúsculas ({@code day|operator|paymentMethod}) e o relatório
 * devolve na mesma forma via {@link #wireName()}.
 *
 * <p>A whitelist é fechada, como a de ordenação do {@code ListProductsUseCase}: valor ausente ou
 * fora dela → 400 {@code VALIDATION_ERROR} com {@code errors[]} citando {@code groupBy} — a string
 * do cliente é resolvida aqui e nunca chega ao SQL.
 */
public enum SalesSummaryGroupBy {
  DAY("day"),
  OPERATOR("operator"),
  PAYMENT_METHOD("paymentMethod");

  /** Nome do campo no {@code errors[]} quando o valor não resolve. */
  private static final String FIELD = "groupBy";

  /** Valores aceitos, na ordem do enum, para a mensagem do 400. */
  private static final String VALUES =
      Arrays.stream(values()).map(SalesSummaryGroupBy::wireName).collect(Collectors.joining(", "));

  private final String wireName;

  SalesSummaryGroupBy(String wireName) {
    this.wireName = wireName;
  }

  /**
   * Nome aceito na query e devolvido na resposta ({@code day}, {@code operator}, {@code
   * paymentMethod}).
   */
  public String wireName() {
    return wireName;
  }

  /**
   * Valor do {@code groupBy} sem diferenciar maiúsculas; ausente ou fora da whitelist → 400 {@code
   * VALIDATION_ERROR} citando o campo.
   */
  public static SalesSummaryGroupBy fromParam(String value) {
    if (value == null || value.isBlank()) {
      throw new FieldValidationException(FIELD, "é obrigatório");
    }
    for (SalesSummaryGroupBy groupBy : values()) {
      if (groupBy.wireName.equalsIgnoreCase(value.trim())) {
        return groupBy;
      }
    }
    throw new FieldValidationException(FIELD, "deve ser um dos valores: " + VALUES);
  }
}
