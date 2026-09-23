package com.minimarket.users.application;

import java.util.List;

/**
 * Projeção de uma role do catálogo com as permissões que ela concede (passo 114): o adaptador monta
 * a partir do banco, o caso de uso devolve e a API mapeia para o DTO. Nenhuma entidade JPA
 * atravessa esta fronteira.
 */
public record RoleSummary(
    String code, String name, String description, boolean system, List<String> permissions) {}
