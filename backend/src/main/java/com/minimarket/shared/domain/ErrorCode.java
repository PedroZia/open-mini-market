package com.minimarket.shared.domain;

import java.util.Locale;

/**
 * Catálogo dos códigos de erro estáveis da API (§9.2 do plano): o {@code code} é o que os clientes
 * usam em lógica, o título é para humano e o status é o HTTP correspondente.
 */
public enum ErrorCode {
  VALIDATION_ERROR(400, "Dados inválidos"),
  IDEMPOTENCY_KEY_REQUIRED(400, "Cabeçalho Idempotency-Key obrigatório"),
  UNKNOWN_ROLE(400, "Papel desconhecido"),
  UNKNOWN_PERMISSION(400, "Permissão desconhecida"),
  INVALID_CURRENT_PASSWORD(400, "Senha atual inválida"),
  INVALID_CREDENTIALS(401, "Credenciais inválidas"),
  SESSION_EXPIRED(401, "Sessão expirada"),
  SESSION_IDLE_TIMEOUT(401, "Sessão expirada por inatividade"),
  ACCESS_DENIED(403, "Acesso negado"),
  NOT_FOUND(404, "Recurso não encontrado"),
  USER_NOT_FOUND(404, "Usuário não encontrado"),
  ROLE_NOT_FOUND(404, "Papel não encontrado"),
  CATEGORY_NOT_FOUND(404, "Categoria não encontrada"),
  PRODUCT_NOT_FOUND(404, "Produto não encontrado"),
  CUSTOMER_NOT_FOUND(404, "Cliente não encontrado"),
  CUSTOMER_INACTIVE(422, "Cliente inativo"),
  CASH_REGISTER_NOT_FOUND(404, "Caixa não encontrado"),
  CASH_SESSION_NOT_OPEN(404, "Sessão de caixa não aberta"),
  CASH_SESSION_NOT_FOUND(404, "Sessão de caixa não encontrada"),
  SALE_NOT_FOUND(404, "Venda não encontrada"),
  SALE_ITEM_NOT_FOUND(404, "Item da venda não encontrado"),
  METHOD_NOT_ALLOWED(405, "Método não permitido"),
  CONFLICT(409, "Conflito de estado"),
  USERNAME_ALREADY_EXISTS(409, "Username já está em uso"),
  CATEGORY_NAME_ALREADY_EXISTS(409, "Nome de categoria já está em uso"),
  BARCODE_ALREADY_EXISTS(409, "Código de barras já está em uso"),
  TAX_ID_ALREADY_EXISTS(409, "CPF já está em uso"),
  CASH_REGISTER_ALREADY_OPEN(409, "Caixa já está aberto"),
  CASH_SESSION_ALREADY_CLOSED(409, "Sessão de caixa já está fechada"),
  CASH_SESSION_REQUIRED(409, "Sessão de caixa aberta obrigatória"),
  IDEMPOTENCY_KEY_REUSED(409, "Chave de idempotência já utilizada"),
  CONCURRENT_MODIFICATION(409, "Modificação concorrente"),
  SALE_NOT_OPEN(409, "Venda não está aberta"),
  SALE_ALREADY_COMPLETED(409, "Venda já concluída"),
  BUSINESS_ERROR(422, "Regra de negócio violada"),
  INSUFFICIENT_STOCK(422, "Estoque insuficiente"),
  PRODUCT_INACTIVE(422, "Produto inativo"),
  DISCOUNT_LIMIT_EXCEEDED(422, "Desconto acima do limite da loja"),
  PAYMENT_EXCEEDS_TOTAL(422, "Pagamento acima do restante da venda"),
  INVALID_TENDERED_AMOUNT(422, "Valor entregue inválido"),
  ACCOUNT_LOCKED(423, "Conta bloqueada"),
  IF_MATCH_REQUIRED(428, "Cabeçalho If-Match obrigatório"),
  RATE_LIMITED(429, "Muitas requisições"),
  INTERNAL_ERROR(500, "Erro interno");

  private static final String TYPE_BASE = "https://minimarket.local/problems/";

  private final int status;
  private final String title;

  ErrorCode(int status, String title) {
    this.status = status;
    this.title = title;
  }

  public int status() {
    return status;
  }

  public String title() {
    return title;
  }

  /** URI que identifica o tipo do problema, derivada do código (RFC 9457). */
  public String type() {
    return TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
