package com.minimarket.customers.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.customers.application.CustomerStore;
import com.minimarket.customers.application.CustomerSummary;
import com.minimarket.customers.application.NewCustomer;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Adaptador JPA da tabela {@code customers}. Sem {@code @Transactional}: a transação é do caso de
 * uso (§2.2, regra 6).
 *
 * <p>Diferente do produto (que precisa do soft-deletado para reativar), cliente não tem reativação:
 * {@link #findById}, {@link #search}, {@link #count}, {@link #update} e {@link #disable} só
 * enxergam cliente vivo. A exceção é {@link #findAnyById}, que enxerga o desativado de propósito —
 * o vínculo da venda (passo 811) precisa saber que o cliente existe, só não está ativo.
 *
 * <p>Implementa a porta {@link CustomerStore}: é por ela que {@code application} grava cliente sem
 * tocar em JPA.
 */
@ApplicationScoped
public class CustomerRepository implements CustomerStore {

  /** SQLState de violação de unique constraint no PostgreSQL. */
  private static final String UNIQUE_VIOLATION = "23505";

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public UUID insert(NewCustomer customer) {
    CustomerEntity entity =
        new CustomerEntity(
            customer.storeId(),
            customer.name(),
            customer.taxId(),
            customer.phone(),
            customer.email(),
            customer.notes());
    entity.assignId(UuidCreator.getTimeOrderedEpoch());
    entityManager.persist(entity);
    flushTranslatingConflicts();
    return entity.getId();
  }

  /** {@inheritDoc} */
  @Override
  public Optional<CustomerSummary> findById(UUID id) {
    return findLiveEntityById(id).map(CustomerRepository::toSummary);
  }

  /**
   * {@inheritDoc}
   *
   * <p>O {@code find} do contexto de persistência enxerga a linha inteira, desativada inclusive —
   * nenhum filtro de {@code deleted_at} aqui, como no {@code ProductRepository.findById}.
   */
  @Override
  public Optional<CustomerSummary> findAnyById(UUID id) {
    return Optional.ofNullable(entityManager.find(CustomerEntity.class, id))
        .map(CustomerRepository::toSummary);
  }

  /** {@inheritDoc} */
  @Override
  public List<CustomerSummary> search(String search, int page, int size) {
    boolean hasTerm = hasTerm(search);
    boolean hasDigits = hasDigits(search);
    TypedQuery<CustomerEntity> query =
        entityManager
            .createQuery(
                "select c from CustomerEntity c where c.deletedAt is null"
                    + termClause(hasTerm, hasDigits)
                    + " order by lower(c.name), c.id",
                CustomerEntity.class)
            .setFirstResult(page * size)
            .setMaxResults(size);
    applyTerm(query, search, hasDigits);
    return query.getResultList().stream().map(CustomerRepository::toSummary).toList();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Conta com a mesma cláusula da busca ({@link #termClause}): o {@code totalItems} não pode
   * divergir do que {@link #search} devolve para o mesmo termo.
   */
  @Override
  public long count(String search) {
    boolean hasTerm = hasTerm(search);
    boolean hasDigits = hasDigits(search);
    TypedQuery<Long> query =
        entityManager.createQuery(
            "select count(c) from CustomerEntity c where c.deletedAt is null"
                + termClause(hasTerm, hasDigits),
            Long.class);
    applyTerm(query, search, hasDigits);
    return query.getSingleResult();
  }

  /**
   * {@inheritDoc}
   *
   * <p>O flush antecipado, como no {@link #insert}, deixa o {@code version} novo visível na
   * projeção devolvida e é o backstop da checagem de CPF do caso de uso: se outra requisição gravar
   * o mesmo documento entre a checagem e este flush, o índice único parcial {@code
   * ux_customers_tax_id} estoura aqui e vira o mesmo 409 do caminho comum, nunca 500.
   */
  @Override
  public Optional<CustomerSummary> update(
      UUID id, String name, String taxId, String phone, String email, String notes) {
    return findLiveEntityById(id)
        .map(
            customer -> {
              customer.updateDetails(name, taxId, phone, email, notes);
              flushTranslatingConflicts();
              return toSummary(customer);
            });
  }

  /**
   * {@inheritDoc}
   *
   * <p>O flush antecipado, como no {@link #insert}, deixa o {@code version} novo visível na
   * projeção devolvida e é o backstop do lock otimista: desativar um cliente alterado por outra
   * requisição entre a leitura e este flush vira {@link ConflictException} com o mesmo código do
   * caminho comum, nunca 500.
   */
  @Override
  public Optional<CustomerSummary> disable(UUID id) {
    return findLiveEntityById(id)
        .map(
            customer -> {
              customer.markDisabled(Instant.now());
              flushTranslatingConflicts();
              return toSummary(customer);
            });
  }

  /** {@inheritDoc} */
  @Override
  public boolean existsActiveTaxId(String taxId) {
    return !entityManager
        .createQuery(
            "select c.id from CustomerEntity c where c.taxId = :taxId and c.deletedAt is null",
            UUID.class)
        .setParameter("taxId", taxId)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }

  /**
   * O {@code existsActiveTaxId} do caso de uso não é atômico: entre a checagem e a escrita, outro
   * request pode gravar o mesmo CPF. O flush antecipado (no {@code insert} e também no {@code
   * update}, que compartilha a tabela) faz a violação do índice único parcial {@code
   * ux_customers_tax_id} aparecer aqui e virar {@link ConflictException} com o mesmo código do
   * caminho comum — o backstop do banco nunca responde 500. {@code customers} só tem esse índice
   * único além da chave primária (o id é UUIDv7 gerado na aplicação), então SQLState 23505 aqui só
   * pode ser CPF duplicado entre clientes vivos; qualquer outra falha de persistência sobe como
   * está.
   *
   * <p>O mesmo flush é o backstop do lock otimista do {@code update}: o Hibernate sinaliza a versão
   * vencida (o {@code where version = ?} não achou a linha) e o stale vira o 409 {@code
   * CONCURRENT_MODIFICATION} do caso de uso.
   */
  private void flushTranslatingConflicts() {
    try {
      entityManager.flush();
    } catch (OptimisticLockException exception) {
      throw new ConflictException(
          ErrorCode.CONCURRENT_MODIFICATION,
          "cliente alterado por outra requisição; recarregue e tente de novo");
    } catch (ConstraintViolationException exception) {
      if (!UNIQUE_VIOLATION.equals(exception.getSQLState())) {
        throw exception;
      }
      throw new ConflictException(ErrorCode.TAX_ID_ALREADY_EXISTS, "CPF já está em uso");
    }
  }

  /** Cliente vivo: quem escreve só alcança linha com {@code deleted_at} nulo. */
  private Optional<CustomerEntity> findLiveEntityById(UUID id) {
    return Optional.ofNullable(entityManager.find(CustomerEntity.class, id))
        .filter(customer -> customer.getDeletedAt() == null);
  }

  /**
   * Cláusulas do termo, fonte única da busca e da contagem. Sem termo não há filtro; com termo,
   * casa o nome por trecho e — quando o termo tem dígitos — o CPF e o telefone pelo valor exato em
   * dígitos, que é como o PDV acha o cliente pelo documento digitado com ou sem máscara.
   */
  private static String termClause(boolean hasTerm, boolean hasDigits) {
    if (!hasTerm) {
      return "";
    }
    return hasDigits
        ? " and (lower(c.name) like :term or c.taxId = :digits or c.phone = :digits)"
        : " and lower(c.name) like :term";
  }

  private static void applyTerm(TypedQuery<?> query, String search, boolean hasDigits) {
    if (!hasTerm(search)) {
      return;
    }
    query.setParameter("term", "%" + search.trim().toLowerCase(Locale.ROOT) + "%");
    if (hasDigits) {
      query.setParameter("digits", digitsOf(search));
    }
  }

  private static boolean hasTerm(String search) {
    return search != null && !search.isBlank();
  }

  /** O termo reduzido a dígitos casa CPF e telefone exatos; sem dígito nenhum, só o nome busca. */
  private static boolean hasDigits(String search) {
    return hasTerm(search) && !digitsOf(search).isEmpty();
  }

  private static String digitsOf(String search) {
    return search.replaceAll("\\D", "");
  }

  /** Projeção do cliente para a porta: nada de entidade JPA na saída. */
  private static CustomerSummary toSummary(CustomerEntity entity) {
    return new CustomerSummary(
        entity.getId(),
        entity.getStoreId(),
        entity.getName(),
        entity.getTaxId(),
        entity.getPhone(),
        entity.getEmail(),
        entity.getNotes(),
        entity.isActive(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getDeletedAt(),
        entity.getVersion());
  }
}
