package com.minimarket.shared.api;

import com.minimarket.shared.domain.Permission;
import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Porteiro declarativo dos endpoints (§6.4, passo 306): o interceptor do módulo {@code auth} exige
 * a permissão antes de a requisição chegar ao corpo do resource. Quem não tem recebe 403 {@code
 * ACCESS_DENIED} no problem+json padrão, pelo mapper de {@code BusinessException}.
 *
 * <p>Na classe vale para todos os métodos dela; no método, vence a da classe.
 *
 * <p>Mora em {@code shared}, e não em {@code auth}, pelo mesmo motivo de {@link Permission}: um
 * resource de {@code users} que importasse {@code auth} fecharia ciclo de módulos ({@code auth} já
 * depende de {@code users}). O {@code shared} não conhece o interceptor — só o binding.
 */
@Documented
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequirePermission {

  /**
   * Permissão exigida pela operação. O membro é {@link Nonbinding} — quem lê o valor é o
   * interceptor, da anotação do método/classe interceptada — para um único interceptor atender
   * todas as permissões. O valor citado na anotação do próprio interceptor é ignorado.
   */
  @Nonbinding
  Permission value();
}
