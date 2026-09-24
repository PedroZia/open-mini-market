package com.minimarket.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Comando da alteração de preço (passo 411): o alvo, o preço novo ainda cru (a normalização de
 * escala é do caso de uso) e o motivo obrigatório que vai para a auditoria.
 */
public record ChangeProductPriceCommand(UUID id, BigDecimal price, String reason) {}
