package com.minimarket.customers.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência do cliente; o adaptador JPA fica em {@code customers.infrastructure}. Só
 * tipos simples atravessam: nem entidade nem JPA chegam a {@code application}.
 */
public interface CustomerStore {

  /** Insere o cliente e devolve o id gerado (UUIDv7) pelo adaptador. */
  UUID insert(NewCustomer customer);

  /**
   * Cliente vivo pelo id. Diferente do produto — que precisa enxergar o soft-deletado para reativar
   * — o desativado nunca aparece: cliente não tem volta, então o 404 do detalhe e o da edição
   * (502b) são o mesmo caso. Vazio quando não existe cliente vivo com o id.
   */
  Optional<CustomerSummary> findById(UUID id);

  /**
   * Cliente pelo id <em>incluindo o desativado</em> — espelha o {@code ProductStore.findById}, que
   * enxerga o soft-deletado: quem decide o que fazer com o estado é o caso de uso. O vínculo da
   * venda (passo 811) precisa distinguir "nunca existiu" (404 {@code CUSTOMER_NOT_FOUND}) de
   * "existe e está desativado" (422 {@code CUSTOMER_INACTIVE}), e o {@link #findById} só enxerga
   * vivo, então não serve para isso. Vazio quando não existe cliente com o id.
   */
  Optional<CustomerSummary> findAnyById(UUID id);

  /**
   * Página de clientes vivos (desativado nunca aparece) ordenada por nome sem diferenciar
   * maiúsculas, com desempate por {@code id} para a paginação não repetir item. {@code search} em
   * branco = sem filtro; com termo, casa nome por trecho sem diferenciar maiúsculas e, quando o
   * termo tem dígitos, casa também {@code taxId} e {@code phone} pelo valor exato em dígitos — é
   * como o PDV acha o cliente pelo documento.
   */
  List<CustomerSummary> search(String search, int page, int size);

  /**
   * Total de clientes vivos que casam com o filtro de {@link #search} (sem paginação), para o
   * {@code totalItems} e o {@code totalPages} da página.
   */
  long count(String search);

  /**
   * Grava nome, CPF, telefone, e-mail e observações do cliente vivo — o status não passa por aqui
   * (o 502b só desativa). Devolve a projeção já atualizada, com o {@code version} novo; vazio
   * quando não existe cliente vivo com o id — o 404 é do caso de uso. CPF já tomado por outro
   * cliente vivo falha com {@code ConflictException(TAX_ID_ALREADY_EXISTS)}.
   */
  Optional<CustomerSummary> update(
      UUID id, String name, String taxId, String phone, String email, String notes);

  /**
   * Soft delete: grava {@code active = false} e {@code deleted_at} de uma vez, o que tira o cliente
   * da busca e do detalhe e libera o CPF para outro cliente, como no índice único parcial. Vazio
   * para id desconhecido ou cliente já desativado — o 404 é do caso de uso. Devolve a projeção já
   * com o estado novo.
   */
  Optional<CustomerSummary> disable(UUID id);

  /**
   * Indica se existe cliente vivo com o CPF informado (só dígitos). A checagem não filtra por loja
   * porque o índice único do banco é {@code (store_id, tax_id)} e o MVP tem loja única (§5.3) —
   * cobrir a tabela inteira é o mesmo escopo da constraint.
   */
  boolean existsActiveTaxId(String taxId);
}
