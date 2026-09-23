package com.minimarket.users.application;

import java.util.UUID;

/**
 * Evento de domínio síncrono (passo 213) que avisa que o acesso do usuário mudou — desativação,
 * reset de senha ou corte das sessões pelo ADMIN — para o módulo {@code auth} revogar as sessões
 * vivas dele na mesma transação. É a comunicação entre módulos prevista no §2.2: {@code users} não
 * pode depender de {@code auth} (o login já faz o caminho inverso) e o evento atravessa a fronteira
 * sem fechar ciclo.
 *
 * <p>{@code reason} é o vocabulário de {@code auth_sessions.revoked_reason} e quem publica o
 * escolhe: {@code USER_DISABLED} na desativação, {@code PASSWORD_RESET} no reset de senha e {@code
 * ADMIN_REVOKE} no corte pelo ADMIN — as constantes moram nos casos de uso donos de cada operação,
 * como em {@code LogoutUseCase.LOGOUT_REASON} no módulo {@code auth}.
 */
public record UserAccessChangedEvent(UUID userId, String reason) {}
