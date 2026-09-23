package com.minimarket.auth.application;

import com.minimarket.auth.domain.SessionClient;
import java.net.InetAddress;
import java.util.UUID;

/**
 * Entrada do {@link LoginUseCase}: a senha chega em texto puro (é o único ponto do fluxo em que ela
 * existe). {@code client} diz de onde vem a tentativa (TUI/WEB) e {@code cashRegisterId}, {@code
 * ip} e {@code userAgent} são opcionais — como a API descobre o client é decisão do passo 205.
 */
public record LoginCommand(
    String username,
    String password,
    SessionClient client,
    UUID cashRegisterId,
    InetAddress ip,
    String userAgent) {}
