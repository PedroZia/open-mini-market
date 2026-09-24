package com.minimarket.cash.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.auth.application.AuthSessionSnapshot;
import com.minimarket.auth.application.AuthSessionStore;
import com.minimarket.auth.application.NewAuthSession;
import com.minimarket.auth.application.UserSessionSummary;
import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link OpenCashSessionUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão e o relógio é fixo, para os instantes da sessão e do movimento serem conferidos
 * campo a campo. O gravador de auditoria também é dublê — o evento é conferido como o caso de uso o
 * entregou, e a gravação de verdade contra o PostgreSQL é coberta pelo {@code
 * OpenCashSessionIntegrationTest}.
 */
class OpenCashSessionUseCaseTest {

  private static final String STORE_CODE = "MATRIZ";
  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final UUID AUTH_SESSION_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000010");
  private static final Instant NOW = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeCashRegisterStore cashRegisterStore = new FakeCashRegisterStore();
  private final FakeCashSessionStore cashSessionStore = new FakeCashSessionStore();
  private final FakeStoreLookup storeLookup = new FakeStoreLookup();
  private final FakeAuthSessionStore authSessionStore = new FakeAuthSessionStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private OpenCashSessionUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new OpenCashSessionUseCase();
    useCase.cashRegisterStore = cashRegisterStore;
    useCase.cashSessionStore = cashSessionStore;
    useCase.storeLookup = storeLookup;
    useCase.authSessionStore = authSessionStore;
    useCase.auditRecorder = auditRecorder;
    useCase.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    useCase.defaultStoreCode = STORE_CODE;
  }

  @Test
  @DisplayName(
      "abre o caixa: sessão OPEN no instante do relógio, movimento OPENING, auditoria e vínculo")
  void opensSessionWithOpeningMovementAuditAndBind() {
    UUID registerId = cashRegisterStore.activeRegister();

    CashSessionSummary result = useCase.execute(command(registerId, "150.00", AUTH_SESSION_ID));

    assertThat(result.id()).isEqualTo(cashSessionStore.generatedId);
    assertThat(result.status()).isEqualTo(CashSessionStatus.OPEN);
    assertThat(result.storeId()).isEqualTo(STORE_ID);
    assertThat(result.cashRegisterId()).isEqualTo(registerId);
    assertThat(result.openedByUserId()).isEqualTo(OPERATOR_ID);
    assertThat(result.openedAt()).isEqualTo(NOW);
    assertThat(result.openingAmount()).isEqualByComparingTo("150.00");

    assertThat(cashSessionStore.inserted.storeId())
        .as("loja vem da configuração, não do comando")
        .isEqualTo(STORE_ID);
    assertThat(cashSessionStore.inserted.cashRegisterId()).isEqualTo(registerId);
    assertThat(cashSessionStore.inserted.openedByUserId()).isEqualTo(OPERATOR_ID);
    assertThat(cashSessionStore.inserted.openedAt()).isEqualTo(NOW);
    assertThat(cashSessionStore.inserted.openingAmount()).isEqualByComparingTo("150.00");

    assertThat(cashSessionStore.movement.type()).isEqualTo(CashMovementType.OPENING);
    assertThat(cashSessionStore.movement.amount())
        .as("abertura entra positiva no ledger")
        .isEqualByComparingTo("150.00");
    assertThat(cashSessionStore.movement.storeId()).isEqualTo(STORE_ID);
    assertThat(cashSessionStore.movement.cashSessionId()).isEqualTo(cashSessionStore.generatedId);
    assertThat(cashSessionStore.movement.createdByUserId()).isEqualTo(OPERATOR_ID);
    assertThat(cashSessionStore.movement.createdAt()).isEqualTo(NOW);
    assertThat(cashSessionStore.movement.paymentMethod()).isNull();
    assertThat(cashSessionStore.movement.referenceType()).isNull();
    assertThat(cashSessionStore.movement.referenceId()).isNull();
    assertThat(cashSessionStore.movement.reason()).isNull();

    assertThat(authSessionStore.bound)
        .as("a sessão autenticada passa a apontar para o caixa aberto")
        .containsExactly(new Bind(AUTH_SESSION_ID, registerId));

    Recorded event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("CASH_SESSION_OPENED");
    assertThat(event.entityType()).isEqualTo("CASH_SESSION");
    assertThat(event.entityId()).isEqualTo(cashSessionStore.generatedId);
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .containsEntry("cashRegisterId", registerId)
        .containsEntry("openingAmount", new BigDecimal("150.00"));
  }

  @Test
  @DisplayName("valor de abertura é normalizado para a escala 2 com arredondamento HALF_UP")
  void normalizesOpeningAmountToScaleTwoHalfUp() {
    useCase.execute(command(cashRegisterStore.activeRegister(), "120.555", null));

    assertThat(cashSessionStore.inserted.openingAmount())
        .as("120.555 arredonda para 120.56")
        .isEqualByComparingTo("120.56");
    assertThat(cashSessionStore.inserted.openingAmount().scale()).isEqualTo(2);
    assertThat(cashSessionStore.movement.amount()).isEqualByComparingTo("120.56");
    assertThat(auditRecorder.only().details())
        .containsEntry("openingAmount", new BigDecimal("120.56"));
  }

  @Test
  @DisplayName("abre com fundo de troco zero: o valor mínimo aceito é zero, não maior que zero")
  void acceptsZeroOpeningAmount() {
    useCase.execute(command(cashRegisterStore.activeRegister(), "0", null));

    assertThat(cashSessionStore.inserted.openingAmount()).isEqualByComparingTo("0.00");
    assertThat(cashSessionStore.movement.amount()).isEqualByComparingTo("0.00");
    assertThat(auditRecorder.only().details())
        .containsEntry("openingAmount", new BigDecimal("0.00"));
  }

  @Test
  @DisplayName("valor de abertura nulo ou negativo é erro de validação sem inserir")
  void rejectsInvalidOpeningAmount() {
    UUID registerId = cashRegisterStore.activeRegister();

    for (BigDecimal openingAmount : Arrays.asList(null, new BigDecimal("-0.01"))) {
      assertThatThrownBy(() -> useCase.execute(command(registerId, openingAmount, null)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(cashSessionStore.inserted).isNull();
    assertThat(authSessionStore.bound).isEmpty();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("caixa desconhecido lança NotFoundException(CASH_REGISTER_NOT_FOUND) sem inserir")
  void rejectsUnknownRegister() {
    assertThatThrownBy(() -> useCase.execute(command(UUID.randomUUID(), "150.00", AUTH_SESSION_ID)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_REGISTER_NOT_FOUND));

    assertThat(cashSessionStore.inserted).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("caixa inativo é o mesmo 404 do inexistente, sem inserir")
  void rejectsInactiveRegister() {
    UUID registerId = cashRegisterStore.inactiveRegister();

    assertThatThrownBy(() -> useCase.execute(command(registerId, "150.00", AUTH_SESSION_ID)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.CASH_REGISTER_NOT_FOUND);
              assertThat(error.getMessage()).contains(registerId.toString());
            });

    assertThat(cashSessionStore.inserted).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("caixa com sessão aberta lança ConflictException(CASH_REGISTER_ALREADY_OPEN)")
  void rejectsAlreadyOpenRegister() {
    UUID registerId = cashRegisterStore.activeRegister();
    cashSessionStore.openSession = openSession(registerId);

    assertThatThrownBy(() -> useCase.execute(command(registerId, "150.00", AUTH_SESSION_ID)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_REGISTER_ALREADY_OPEN));

    assertThat(cashSessionStore.inserted).as("nada é gravado na recusa").isNull();
    assertThat(authSessionStore.bound).isEmpty();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("violação do índice único no insert vira o mesmo 409 do caminho comum")
  void translatesInsertConflictFromDatabaseBackstop() {
    UUID registerId = cashRegisterStore.activeRegister();
    cashSessionStore.insertFailure =
        new ConflictException(ErrorCode.CASH_REGISTER_ALREADY_OPEN, "caixa já está aberto");

    assertThatThrownBy(() -> useCase.execute(command(registerId, "150.00", AUTH_SESSION_ID)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_REGISTER_ALREADY_OPEN));

    assertThat(cashSessionStore.movement).as("a corrida para no insert").isNull();
    assertThat(authSessionStore.bound).isEmpty();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName(
      "sem sessão autenticada a abertura acontece sem vínculo: nada a apontar para o caixa")
  void skipsBindWithoutAuthSession() {
    useCase.execute(command(cashRegisterStore.activeRegister(), "150.00", null));

    assertThat(authSessionStore.bound).isEmpty();
    assertThat(auditRecorder.only().action()).isEqualTo("CASH_SESSION_OPENED");
  }

  /** Comando da abertura com o operador fixo do cenário. */
  private static OpenCashSessionCommand command(
      UUID cashRegisterId, String openingAmount, UUID authSessionId) {
    return command(cashRegisterId, new BigDecimal(openingAmount), authSessionId);
  }

  private static OpenCashSessionCommand command(
      UUID cashRegisterId, BigDecimal openingAmount, UUID authSessionId) {
    return new OpenCashSessionCommand(cashRegisterId, openingAmount, OPERATOR_ID, authSessionId);
  }

  /** Sessão aberta como o adaptador a projetaria, para o cenário de caixa já aberto. */
  private static CashSessionSummary openSession(UUID cashRegisterId) {
    return new CashSessionSummary(
        UUID.randomUUID(),
        STORE_ID,
        cashRegisterId,
        CashSessionStatus.OPEN,
        OPERATOR_ID,
        NOW,
        new BigDecimal("50.00"),
        null,
        null,
        null,
        null,
        null,
        null,
        NOW,
        NOW,
        0L);
  }

  /** Vínculo pedido à porta de sessão: o par (sessão autenticada, caixa) do cenário. */
  private record Bind(UUID authSessionId, UUID cashRegisterId) {}

  /**
   * Dublê de {@link AuthSessionStore}: guarda os vínculos pedidos e nada mais — o resto do contrato
   * de sessão é exercitado pelos testes do módulo {@code auth}.
   */
  private static final class FakeAuthSessionStore implements AuthSessionStore {

    private final List<Bind> bound = new ArrayList<>();

    @Override
    public void bindCashRegister(UUID id, UUID cashRegisterId) {
      bound.add(new Bind(id, cashRegisterId));
    }

    @Override
    public UUID insert(NewAuthSession session) {
      throw new UnsupportedOperationException("insert não é usado por OpenCashSession");
    }

    @Override
    public Optional<AuthSessionSnapshot> findActiveByTokenHash(String tokenHash) {
      throw new UnsupportedOperationException(
          "findActiveByTokenHash não é usado por OpenCashSession");
    }

    @Override
    public Optional<AuthSessionSnapshot> findActiveById(UUID id) {
      throw new UnsupportedOperationException("findActiveById não é usado por OpenCashSession");
    }

    @Override
    public List<UserSessionSummary> listActiveByUser(UUID userId) {
      throw new UnsupportedOperationException("listActiveByUser não é usado por OpenCashSession");
    }

    @Override
    public void touchLastSeen(UUID id, Instant lastSeenAt) {
      throw new UnsupportedOperationException("touchLastSeen não é usado por OpenCashSession");
    }

    @Override
    public void revoke(UUID id, String reason, Instant revokedAt) {
      throw new UnsupportedOperationException("revoke não é usado por OpenCashSession");
    }

    @Override
    public int revokeAllByUser(UUID userId, String reason, Instant revokedAt) {
      throw new UnsupportedOperationException("revokeAllByUser não é usado por OpenCashSession");
    }

    @Override
    public int revokeAllByUserExcept(
        UUID userId, UUID sessionId, String reason, Instant revokedAt) {
      throw new UnsupportedOperationException(
          "revokeAllByUserExcept não é usado por OpenCashSession");
    }
  }

  /**
   * Dublê de {@link CashRegisterStore}: simula caixas ativos e inativos para a checagem da
   * abertura.
   */
  private static final class FakeCashRegisterStore implements CashRegisterStore {

    private final Map<UUID, Boolean> registers = new HashMap<>();

    private UUID activeRegister() {
      return register(true);
    }

    private UUID inactiveRegister() {
      return register(false);
    }

    private UUID register(boolean active) {
      UUID id = UUID.randomUUID();
      registers.put(id, active);
      return id;
    }

    @Override
    public Optional<CashRegisterSummary> findActiveById(UUID id) {
      return Boolean.TRUE.equals(registers.get(id))
          ? Optional.of(new CashRegisterSummary(id, "CAIXA-01", "Caixa 1"))
          : Optional.empty();
    }

    @Override
    public List<CashRegisterSummary> listActive() {
      throw new UnsupportedOperationException("listActive não é usado por OpenCashSession");
    }
  }

  /**
   * Dublê de {@link CashSessionStore}: guarda a última sessão e o último movimento inseridos e
   * devolve a projeção que o adaptador JPA devolveria na releitura pós-insert.
   */
  private static final class FakeCashSessionStore implements CashSessionStore {

    /** Instante que o banco completaria no {@code created_at} da linha relida. */
    private static final Instant CREATED_AT = NOW.plusSeconds(1);

    private final UUID generatedId = UUID.randomUUID();
    private CashSessionSummary openSession;
    private NewCashSession inserted;
    private NewCashMovement movement;
    private ConflictException insertFailure;

    @Override
    public UUID insert(NewCashSession session) {
      if (insertFailure != null) {
        throw insertFailure;
      }
      inserted = session;
      return generatedId;
    }

    @Override
    public Optional<CashSessionSummary> findOpenByRegister(UUID cashRegisterId) {
      return Optional.ofNullable(openSession)
          .filter(open -> open.cashRegisterId().equals(cashRegisterId));
    }

    /** O que o adaptador JPA devolveria na releitura logo após o insert: defaults do banco. */
    @Override
    public Optional<CashSessionSummary> findById(UUID id) {
      if (inserted == null || !generatedId.equals(id)) {
        return Optional.empty();
      }
      return Optional.of(
          new CashSessionSummary(
              generatedId,
              inserted.storeId(),
              inserted.cashRegisterId(),
              CashSessionStatus.OPEN,
              inserted.openedByUserId(),
              inserted.openedAt(),
              inserted.openingAmount(),
              null,
              null,
              null,
              null,
              null,
              null,
              CREATED_AT,
              CREATED_AT,
              0L));
    }

    @Override
    public UUID insertMovement(NewCashMovement newMovement) {
      movement = newMovement;
      return UUID.randomUUID();
    }

    @Override
    public Map<CashMovementType, BigDecimal> sumByType(UUID cashSessionId) {
      throw new UnsupportedOperationException("sumByType não é usado por OpenCashSession");
    }

    @Override
    public Optional<CashSessionSummary> lockById(UUID id) {
      throw new UnsupportedOperationException("lockById não é usado por OpenCashSession");
    }

    @Override
    public CashSessionSummary close(
        UUID id,
        BigDecimal countedAmount,
        BigDecimal expectedAmount,
        BigDecimal differenceAmount,
        String closingNotes,
        UUID closedByUserId,
        Instant closedAt) {
      throw new UnsupportedOperationException("close não é usado por OpenCashSession");
    }
  }

  /** Dublê de {@link StoreLookup}: devolve a loja configurada, como o seed da V1. */
  private static final class FakeStoreLookup implements StoreLookup {

    @Override
    public Optional<Store> findByCode(String code) {
      return STORE_CODE.equals(code)
          ? Optional.of(new Store(STORE_ID, STORE_CODE, "Matriz", false, new BigDecimal("10.00")))
          : Optional.empty();
    }

    @Override
    public Optional<Store> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por OpenCashSession");
    }
  }

  /**
   * Dublê de {@link AuditRecorder}: guarda o que o caso de uso pediu para gravar, sem CDI e sem
   * banco. A subclasse só sobrescreve {@code record} — o caminho de verdade (contexto + INSERT) é
   * do passo 303 e tem teste próprio contra PostgreSQL.
   */
  private static final class FakeAuditRecorder extends AuditRecorder {

    private final List<Recorded> recorded = new ArrayList<>();

    @Override
    public void record(
        String action,
        String entityType,
        UUID entityId,
        String reason,
        Map<String, Object> details) {
      recorded.add(new Recorded(action, entityType, entityId, reason, details));
    }

    /** Único evento do cenário; o teste falha se o caso de uso gravou zero ou dois. */
    private Recorded only() {
      assertThat(recorded).as("eventos de auditoria do cenário").hasSize(1);
      return recorded.getFirst();
    }
  }

  /** Evento como o caso de uso o entregou ao gravador. */
  private record Recorded(
      String action,
      String entityType,
      UUID entityId,
      String reason,
      Map<String, Object> details) {}
}
