package com.minimarket.cash.application;

import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.users.application.UserStore;
import com.minimarket.users.application.UserSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Lista os caixas ativos com o status atual (passo 602) — é o que a TUI consulta para escolher o
 * caixa no login (§9.3).
 *
 * <p>Leitura pura, sem {@code @Transactional}: não grava nada e as consultas são idas só ao banco —
 * mesma escolha do {@code ListCategoriesUseCase}. A composição mora aqui e não na API: o status sai
 * da sessão aberta do caixa ({@link CashSessionStore#findOpenByRegister}) e o operador, do usuário
 * que a abriu. {@code application} de um módulo pode usar o {@code application} de outro — {@code
 * users} não conhece {@code cash}, sem ciclo.
 */
@ApplicationScoped
public class ListCashRegistersUseCase {

  @Inject CashRegisterStore cashRegisterStore;

  @Inject CashSessionStore cashSessionStore;

  @Inject UserStore userStore;

  /** Caixas ativos ordenados por código, cada um com o status e o operador da sessão atual. */
  public List<CashRegisterView> execute() {
    return cashRegisterStore.listActive().stream().map(this::withCurrentSession).toList();
  }

  /** Sem sessão aberta o caixa está {@code CLOSED} e não tem operador. */
  private CashRegisterView withCurrentSession(CashRegisterSummary register) {
    return cashSessionStore
        .findOpenByRegister(register.id())
        .map(
            session ->
                new CashRegisterView(
                    register.id(),
                    register.code(),
                    register.name(),
                    session.status(),
                    operatorName(session)))
        .orElseGet(
            () ->
                new CashRegisterView(
                    register.id(),
                    register.code(),
                    register.name(),
                    CashSessionStatus.CLOSED,
                    null));
  }

  /**
   * Nome de exibição de quem abriu a sessão. Usuário soft-deletado não aparece para {@code
   * findSummaryById} — nesse caso o operador fica nulo, sem derrubar a listagem.
   */
  private String operatorName(CashSessionSummary session) {
    return userStore
        .findSummaryById(session.openedByUserId())
        .map(UserSummary::displayName)
        .orElse(null);
  }
}
