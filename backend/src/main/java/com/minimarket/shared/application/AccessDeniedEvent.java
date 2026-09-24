package com.minimarket.shared.application;

/**
 * Tentativa de acesso negada por falta de permissão (passo 309), publicada pelo {@code
 * BusinessExceptionMapper} a cada 403 — e só nele.
 *
 * <p>O evento existe para o {@code shared} avisar o {@code audit} sem depender dele (audit já
 * depende de shared; o contrário fecharia ciclo de módulos, §2.2). Quem grava é o observer de
 * {@code audit.application}, em transação própria: quando o mapper roda, a transação do caso de uso
 * já foi desfeita e sem transação nova o registro se perderia.
 *
 * @param method método HTTP da requisição ({@code GET}, {@code POST}...)
 * @param route caminho da requisição, o mesmo {@code instance} do problem+json
 * @param requiredPermission código da permissão exigida; nulo quando a recusa não tem uma
 */
public record AccessDeniedEvent(String method, String route, String requiredPermission) {}
