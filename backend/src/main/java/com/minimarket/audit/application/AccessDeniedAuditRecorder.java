package com.minimarket.audit.application;

import com.minimarket.shared.application.AccessDeniedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Grava o evento {@code ACCESS_DENIED} (§7.2) em transação própria (passo 309).
 *
 * <p>O 403 só chega ao {@code BusinessExceptionMapper} depois de a transação do caso de uso ser
 * desfeita — o interceptor de permissão derruba a transação antes de o mapper rodar. Sem uma
 * transação nova o registro se perderia; por isso {@code REQUIRES_NEW}.
 *
 * <p>Quem tem a anotação é este bean, chamado pelo {@link AccessDeniedAuditObserver}, e não o
 * método observer: interceptor em método observer não é garantido, e a chamada através do proxy
 * deste bean é.
 *
 * <p>Sem {@code try/catch}: falha ao gravar sobe e derruba a requisição (§7.1) — auditoria que
 * perde evento em silêncio é pior do que erro na cara.
 */
@ApplicationScoped
public class AccessDeniedAuditRecorder {

  /** Ação do catálogo de §7.2. */
  static final String ACTION = "ACCESS_DENIED";

  @Inject AuditRecorder auditRecorder;

  /**
   * Sem entidade: o alvo é a própria requisição recusada, e o que a identifica (rota, método,
   * permissão) vai em {@code details}. O motivo fica nulo — a permissão exigida já diz o porquê.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void record(AccessDeniedEvent event) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("route", event.route());
    details.put("method", event.method());
    details.put("permission", event.requiredPermission());
    auditRecorder.record(ACTION, null, null, null, details);
  }
}
