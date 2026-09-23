package com.minimarket.users.application;

import java.util.List;

/** Página de usuários já validada e limitada pelo {@link ListUsersUseCase}. */
public record UserPage(
    List<UserSummary> items, int page, int size, long totalItems, int totalPages) {}
