package com.minimarket.auth.api;

import com.minimarket.auth.application.AuthorizationService;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Method;

/**
 * Interceptor CDI do {@link RequirePermission} (passo 306): resolve a permissão da anotação do
 * método — sem ela, a da classe — e delega ao {@link AuthorizationService}. A exceção sobe sem
 * tratamento: o {@code BusinessExceptionMapper} responde 403 {@code ACCESS_DENIED} em problem+json,
 * então o interceptor não escreve HTTP nem conhece JAX-RS.
 */
@Interceptor
@RequirePermission(Permission.USER_READ) // @Nonbinding: o valor real vem do alvo, este é ignorado
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100) // antes dos interceptores de aplicação
public class RequirePermissionInterceptor {

  @Inject AuthorizationService authorizationService;

  @AroundInvoke
  Object requirePermission(InvocationContext context) throws Exception {
    Permission permission = permissionOf(context.getMethod());
    if (permission != null) {
      authorizationService.require(permission);
    }
    return context.proceed();
  }

  /** Permissão do método; sem ela, a da classe que o declara (a anotação do método vence). */
  private static Permission permissionOf(Method method) {
    RequirePermission onMethod = method.getAnnotation(RequirePermission.class);
    if (onMethod != null) {
      return onMethod.value();
    }
    RequirePermission onClass = method.getDeclaringClass().getAnnotation(RequirePermission.class);
    return onClass == null ? null : onClass.value();
  }
}
