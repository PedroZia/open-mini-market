package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.cash.application.CashSessionStore;
import com.minimarket.cash.application.CashSessionSummary;
import com.minimarket.cash.application.NewCashMovement;
import com.minimarket.cash.application.NewCashSession;
import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link CreateSaleUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão e o relógio é fixo, para o instante da venda ser conferido campo a campo. O
 * gravador de auditoria também é dublê — o evento é conferido como o caso de uso o entregou, e a
 * gravação de verdade contra o PostgreSQL é coberta pelo {@code CreateSaleIntegrationTest}.
 */
class CreateSaleUseCaseTest {

  private static final String STORE_CODE = "MATRIZ";
  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final UUID CASH_SESSION_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000020");
  private static final Instant NOW = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeCashSessionStore cashSessionStore = new FakeCashSessionStore();
  private final FakeSaleStore saleStore = new FakeSaleStore();
  private final FakeSaleNumberAllocator saleNumberAllocator = new FakeSaleNumberAllocator();
  private final FakeStoreLookup storeLookup = new FakeStoreLookup();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private CreateSaleUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new CreateSaleUseCase();
    useCase.cashSessionStore = cashSessionStore;
    useCase.saleStore = saleStore;
    useCase.saleNumberAllocator = saleNumberAllocator;
    useCase.storeLookup = storeLookup;
    useCase.auditRecorder = auditRecorder;
    useCase.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    useCase.defaultStoreCode = STORE_CODE;
  }

  @Test
  @DisplayName(
      "abre a venda: número do alocador, venda OPEN vazia com totais zero, insert e auditoria")
  void opensSaleWithAllocatedNumberZeroTotalsAndAudit() {
    UUID registerId = cashRegisterStore();
    saleNumberAllocator.allocatedNumber = 42L;

    Sale sale = useCase.execute(new CreateSaleCommand(registerId, OPERATOR_ID));

    assertThat(sale.id().version()).as("id da venda é UUIDv7").isEqualTo(7);
    assertThat(sale.storeId()).as("loja vem da configuração, não do comando").isEqualTo(STORE_ID);
    assertThat(sale.number()).isEqualTo(42L);
    assertThat(sale.cashSessionId()).isEqualTo(CASH_SESSION_ID);
    assertThat(sale.cashRegisterId()).isEqualTo(registerId);
    assertThat(sale.operatorUserId()).isEqualTo(OPERATOR_ID);
    assertThat(sale.status()).isEqualTo(SaleStatus.OPEN);
    assertThat(sale.createdAt()).isEqualTo(NOW);
    assertThat(sale.notes()).isNull();
    assertThat(sale.customerId()).as("cliente é do passo 811").isNull();
    assertThat(sale.completedAt()).isNull();

    assertThat(sale.items()).isEmpty();
    assertThat(sale.itemCount()).isZero();
    assertThat(sale.subtotal()).isEqualByComparingTo("0.00");
    assertThat(sale.discountAmount()).isEqualByComparingTo("0.00");
    assertThat(sale.total()).isEqualByComparingTo("0.00");

    assertThat(saleNumberAllocator.allocatedStoreId)
        .as("a série é da loja configurada")
        .isEqualTo(STORE_ID);
    assertThat(saleStore.inserted).as("a venda criada é a que vai para o banco").isSameAs(sale);

    Recorded event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("SALE_CREATED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(sale.id());
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .containsEntry("number", 42L)
        .containsEntry("cashSessionId", CASH_SESSION_ID)
        .containsEntry("cashRegisterId", registerId);
  }

  @Test
  @DisplayName("sem vínculo de caixa lança ForbiddenException(ACCESS_DENIED) sem alocar número")
  void rejectsSessionWithoutBoundRegister() {
    assertThatThrownBy(() -> useCase.execute(new CreateSaleCommand(null, OPERATOR_ID)))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED));

    assertThat(saleNumberAllocator.allocatedStoreId)
        .as("a recusa acontece antes de tocar na série")
        .isNull();
    assertThat(saleStore.inserted).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("caixa sem sessão aberta lança ConflictException(CASH_SESSION_REQUIRED) sem gravar")
  void rejectsRegisterWithoutOpenSession() {
    UUID registerId = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.execute(new CreateSaleCommand(registerId, OPERATOR_ID)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_REQUIRED);
              assertThat(error.getMessage()).contains(registerId.toString());
            });

    assertThat(saleNumberAllocator.allocatedStoreId)
        .as("a recusa acontece antes de tocar na série")
        .isNull();
    assertThat(saleStore.inserted).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  /** Caixa com sessão de caixa aberta no dublê; devolve o id do caixa do cenário. */
  private UUID cashRegisterStore() {
    UUID registerId = UUID.randomUUID();
    cashSessionStore.openSession = openSession(registerId);
    return registerId;
  }

  /** Sessão aberta como o adaptador a projetaria, para o cenário de caixa aberto. */
  private static CashSessionSummary openSession(UUID cashRegisterId) {
    return new CashSessionSummary(
        CASH_SESSION_ID,
        STORE_ID,
        cashRegisterId,
        CashSessionStatus.OPEN,
        OPERATOR_ID,
        NOW,
        new BigDecimal("100.00"),
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

  /**
   * Dublê de {@link CashSessionStore}: devolve a sessão aberta do cenário (ou vazio) e nada mais —
   * o resto do contrato de caixa é exercitado pelos testes do módulo {@code cash}.
   */
  private static final class FakeCashSessionStore implements CashSessionStore {

    private CashSessionSummary openSession;

    @Override
    public Optional<CashSessionSummary> findOpenByRegister(UUID cashRegisterId) {
      return Optional.ofNullable(openSession)
          .filter(open -> open.cashRegisterId().equals(cashRegisterId));
    }

    @Override
    public UUID insert(NewCashSession session) {
      throw new UnsupportedOperationException("insert não é usado por CreateSale");
    }

    @Override
    public Optional<CashSessionSummary> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por CreateSale");
    }

    @Override
    public UUID insertMovement(NewCashMovement movement) {
      throw new UnsupportedOperationException("insertMovement não é usado por CreateSale");
    }

    @Override
    public Map<CashMovementType, BigDecimal> sumByType(UUID cashSessionId) {
      throw new UnsupportedOperationException("sumByType não é usado por CreateSale");
    }

    @Override
    public Optional<CashSessionSummary> lockById(UUID id) {
      throw new UnsupportedOperationException("lockById não é usado por CreateSale");
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
      throw new UnsupportedOperationException("close não é usado por CreateSale");
    }
  }

  /**
   * Dublê de {@link SaleStore}: guarda a venda inserida e nada mais — leitura, update e busca são
   * exercitados pelo teste de persistência do passo 803.
   */
  private static final class FakeSaleStore implements SaleStore {

    private Sale inserted;

    @Override
    public void insert(Sale sale) {
      inserted = sale;
    }

    @Override
    public Optional<Sale> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por CreateSale");
    }

    @Override
    public void update(Sale sale) {
      throw new UnsupportedOperationException("update não é usado por CreateSale");
    }

    @Override
    public List<SaleSummary> search(
        Instant from,
        Instant to,
        SaleStatus status,
        UUID cashSessionId,
        UUID operatorUserId,
        int page,
        int size) {
      throw new UnsupportedOperationException("search não é usado por CreateSale");
    }

    @Override
    public long count(
        Instant from, Instant to, SaleStatus status, UUID cashSessionId, UUID operatorUserId) {
      throw new UnsupportedOperationException("count não é usado por CreateSale");
    }

    @Override
    public Optional<Sale> lockById(UUID id) {
      throw new UnsupportedOperationException("lockById não é usado por CreateSale");
    }

    @Override
    public boolean existsOpenByCashSession(UUID cashSessionId) {
      throw new UnsupportedOperationException("existsOpenByCashSession não é usado por CreateSale");
    }
  }

  /** Dublê de {@link SaleNumberAllocator}: devolve o número do cenário e guarda a loja pedida. */
  private static final class FakeSaleNumberAllocator implements SaleNumberAllocator {

    private long allocatedNumber = 1L;
    private UUID allocatedStoreId;

    @Override
    public long nextNumber(UUID storeId) {
      allocatedStoreId = storeId;
      return allocatedNumber;
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
      throw new UnsupportedOperationException("findById não é usado por CreateSale");
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
