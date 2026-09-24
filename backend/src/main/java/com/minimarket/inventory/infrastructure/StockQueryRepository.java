package com.minimarket.inventory.infrastructure;

import com.minimarket.inventory.application.StockItemSummary;
import com.minimarket.inventory.application.StockQueryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Adaptador da consulta de estoque (passo 704) em SQL nativo <em>somente leitura</em>: a lista
 * cruza o saldo ({@code product_stocks}, deste módulo) com o cadastro ({@code products}, do {@code
 * catalog}) e precisa filtrar, ordenar e paginar os dois na mesma consulta. O JPQL chegaria ao
 * produto só importando a entidade do outro módulo — proibido pelo §2.2 —, então as tabelas são
 * referenciadas por nome e a projeção sai em {@link StockItemSummary}: entidade JPA nunca chega à
 * {@code application}. Nenhuma escrita passa por aqui.
 *
 * <p>O {@code left join} preserva o produto que nunca teve movimento (saldo zero) e o filtro {@code
 * lowStock} repete a expressão do {@code lowStock()} da projeção, para o flag e o filtro nunca
 * divergirem: {@code true} devolve mínimo configurado com saldo no mínimo ou abaixo, {@code false}
 * o complemento. O termo de busca casa o nome sem diferenciar maiúsculas ou o barcode exato
 * (aparado).
 */
@ApplicationScoped
public class StockQueryRepository implements StockQueryStore {

  /** Colunas do item, na mesma ordem em que {@link #toSummary} as lê. */
  private static final String COLUMNS =
      "select p.id, p.name, p.barcode, p.unit, coalesce(s.quantity, 0) as quantity, p.min_quantity";

  /**
   * Join do saldo com o cadastro: a loja entra nos dois lados — o produto pertence a ela e o saldo
   * é o do par (loja, produto), único pela constraint da V16.
   */
  private static final String FROM =
      " from products p left join product_stocks s"
          + " on s.product_id = p.id and s.store_id = :storeId";

  @Inject EntityManager entityManager;

  /** {@inheritDoc} */
  @Override
  public List<StockItemSummary> search(
      UUID storeId, String search, Boolean lowStock, int page, int size) {
    Query query =
        entityManager.createNativeQuery(
            COLUMNS
                + FROM
                + " where "
                + filters(search, lowStock)
                + " order by p.name asc, p.id"
                + " offset :offset rows fetch first :size rows only");
    bindFilters(query, storeId, search);
    // long: page é int do cliente e o produto page * size estouraria antes de chegar ao banco.
    query.setParameter("offset", (long) page * size);
    query.setParameter("size", size);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = query.getResultList();
    return rows.stream().map(StockQueryRepository::toSummary).toList();
  }

  /** {@inheritDoc} */
  @Override
  public long count(UUID storeId, String search, Boolean lowStock) {
    Query query =
        entityManager.createNativeQuery(
            "select count(*)" + FROM + " where " + filters(search, lowStock));
    bindFilters(query, storeId, search);
    return ((Number) query.getSingleResult()).longValue();
  }

  /**
   * Cláusulas comuns da lista e da contagem: produto vivo da loja, termo (nome sem diferenciar
   * maiúsculas ou barcode exato) e o filtro de estoque baixo. Fonte única para a contagem não
   * divergir da lista.
   */
  private static String filters(String search, Boolean lowStock) {
    StringBuilder where = new StringBuilder("p.deleted_at is null and p.store_id = :storeId");
    if (hasTerm(search)) {
      where.append(" and (lower(p.name) like :nameTerm or p.barcode = :barcodeTerm)");
    }
    if (lowStock != null) {
      // Mesma expressão do lowStock() da projeção: mínimo configurado e saldo no mínimo ou abaixo;
      // o complemento é o "not" dela, que em SQL já cobre o produto sem mínimo.
      String low = "(p.min_quantity is not null and coalesce(s.quantity, 0) <= p.min_quantity)";
      where.append(lowStock ? " and " : " and not ").append(low);
    }
    return where.toString();
  }

  private static void bindFilters(Query query, UUID storeId, String search) {
    query.setParameter("storeId", storeId);
    if (hasTerm(search)) {
      String term = search.trim();
      query.setParameter("nameTerm", "%" + term.toLowerCase(Locale.ROOT) + "%");
      query.setParameter("barcodeTerm", term);
    }
  }

  private static boolean hasTerm(String search) {
    return search != null && !search.isBlank();
  }

  /** Projeção da linha para a porta: nada de entidade JPA na saída. */
  private static StockItemSummary toSummary(Object[] row) {
    return new StockItemSummary(
        (UUID) row[0],
        (String) row[1],
        (String) row[2],
        (String) row[3],
        (BigDecimal) row[4],
        (BigDecimal) row[5]);
  }
}
