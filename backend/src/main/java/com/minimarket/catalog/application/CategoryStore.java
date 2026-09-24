package com.minimarket.catalog.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência da categoria; o adaptador JPA fica em {@code catalog.infrastructure}. Só
 * tipos simples atravessam: nem entidade nem JPA chegam a {@code application}.
 */
public interface CategoryStore {

  /** Insere a categoria e devolve o id gerado (UUIDv7) pelo adaptador. */
  UUID insert(NewCategory category);

  /** Categoria pelo id; vazio quando não existe. Desativada continua sendo devolvida. */
  Optional<CategorySummary> findById(UUID id);

  /** Todas as categorias, ativas e desativadas, ordenadas por {@code sort_order} e nome. */
  List<CategorySummary> findAll();

  /**
   * Grava nome, categoria pai e ordenação, sem mexer em {@code active} (isso é do {@link
   * #deactivate}). Id desconhecido é no-op — a existência é validada pelo caso de uso antes da
   * chamada.
   */
  void update(UUID id, String name, UUID parentId, int sortOrder);

  /**
   * Desativa a categoria: {@code active = false}, sem apagar a linha — produtos e vendas continuam
   * podendo referenciá-la. Id desconhecido é no-op.
   */
  void deactivate(UUID id);

  /**
   * Indica se o nome já está em uso. A checagem não filtra por {@code active} porque o índice único
   * do banco é {@code (store_id, name)}: nome de categoria desativada continua ocupado. Como o MVP
   * tem loja única (§5.3), cobrir a tabela inteira é o mesmo escopo da constraint.
   */
  boolean existsByName(String name);

  /** Como {@link #existsByName}, ignorando a própria categoria: é o caso do update. */
  boolean existsByNameExceptId(String name, UUID id);

  /**
   * Indica se existe categoria com o id, ativa ou desativada: quem decide se a categoria pode
   * receber produto é o caso de uso; a checagem não filtra {@code active}.
   */
  boolean existsById(UUID id);
}
