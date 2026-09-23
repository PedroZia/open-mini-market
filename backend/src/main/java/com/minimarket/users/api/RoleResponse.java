package com.minimarket.users.api;

import java.util.List;

/**
 * Role exposta pela API (§9.3): código, nome, descrição, se é de sistema e as permissões que
 * concede, em ordem alfabética. Nunca carrega entidade JPA.
 */
public record RoleResponse(
    String code, String name, String description, boolean system, List<String> permissions) {}
