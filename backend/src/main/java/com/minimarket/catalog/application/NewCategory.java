package com.minimarket.catalog.application;

import java.util.UUID;

/**
 * Dados que a porta {@link CategoryStore} precisa para inserir uma categoria nova. O {@code
 * storeId} chega já resolvido pelo caso de uso (loja única do MVP, §5.3) e a categoria nasce ativa,
 * como no default da tabela.
 */
public record NewCategory(UUID storeId, String name, UUID parentId, int sortOrder) {}
