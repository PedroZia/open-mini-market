package com.minimarket.inventory.application;

import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Única porta de alteração de saldo de estoque (passo 703, BR-08): nenhum saldo é escrito fora
 * daqui, e toda alteração vira uma linha do ledger com o {@code balance_after} correspondente — o
 * saldo de {@code product_stocks} é o cache do último movimento.
 *
 * <p>Uma chamada = uma transação (§2.2, regra 6): a transação é demarcada aqui, nunca no
 * repositório. Cada movimento segue a ordem insert → lock → conferir → gravar saldo → gravar
 * ledger: o {@code insertIfAbsent} cria a linha de saldo sob demanda (o primeiro movimento de um
 * produto), o {@code lockByProduct} trava a linha com {@code SELECT ... FOR UPDATE} e só então o
 * saldo é lido e conferido — ler antes do insert encontraria vazio no primeiro movimento e o lock
 * não teria o que travar.
 *
 * <p>O lote ({@link #applyMovements}) ordena os comandos por {@code product_id} antes de tocar nas
 * linhas (§8): duas vendas com os mesmos produtos em ordens opostas pegariam os locks em ordens
 * opostas e poderiam se travar mutuamente (deadlock); a ordem total única do {@link
 * UUID#compareTo(UUID)} elimina o ciclo. Os movimentos são aplicados em série, cada um enxergando o
 * saldo que o anterior deixou, e a chamada inteira usa um único {@link Clock#instant()} — os
 * movimentos do mesmo lote compartilham o {@code created_at}, nunca o {@code now()} do banco.
 *
 * <p>BR-09: se o saldo ficaria negativo e a loja tem {@code allow_negative_stock=false}, o
 * movimento é recusado com {@code 422 INSUFFICIENT_STOCK} antes de qualquer gravação — a exceção
 * derruba a transação, então nem o saldo nem o ledger mudam. Com a flag ligada (default do §5.3), o
 * saldo fica negativo e é registrado, como o PDV espera.
 *
 * <p>A loja é a configurada ({@code minimarket.store.default-code}), como no {@code
 * CreateProductUseCase.currentStoreId()}: o MVP tem loja única (§5.4) e a loja nunca vem do
 * comando. Sem auditoria, endpoint, permissão ou idempotência neste passo: o {@code STOCK_ADJUSTED}
 * é do passo 705, a consulta do 704 e a idempotência de quem move dinheiro/estoque é da API.
 */
@ApplicationScoped
public class StockService {

  @Inject ProductStockStore productStockStore;

  @Inject StockMovementStore stockMovementStore;

  @Inject StoreLookup storeLookup;

  /** Relógio da aplicação: o {@code created_at} dos movimentos, nunca o {@code now()} do banco. */
  @Inject Clock clock;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /**
   * Aplica um movimento e devolve o saldo antes/depois calculado sob o lock. Delega ao lote com uma
   * lista de um, para a regra de ordenação e de instante único viver num lugar só.
   */
  @Transactional
  public AppliedStockMovement applyMovement(ApplyStockMovementCommand command) {
    return applyMovements(List.of(command)).getFirst();
  }

  /**
   * Aplica o lote em série e devolve os resultados <em>na ordem em que aplicou</em> (a ordem dos
   * comandos ordenados por {@code product_id}), não na ordem de entrada. Lista vazia devolve lista
   * vazia sem tocar no banco.
   */
  @Transactional
  public List<AppliedStockMovement> applyMovements(List<ApplyStockMovementCommand> commands) {
    if (commands.isEmpty()) {
      return List.of();
    }
    Instant createdAt = clock.instant();
    return commands.stream()
        .sorted(Comparator.comparing(ApplyStockMovementCommand::productId))
        .map(command -> apply(command, createdAt))
        .toList();
  }

  /**
   * Aplica um movimento já com o instante do lote: cria o saldo sob demanda, trava a linha, confere
   * o resultado contra a flag da loja, grava o saldo novo e insere o movimento do ledger com o
   * {@code balance_after} — tudo na transação da chamada.
   */
  private AppliedStockMovement apply(ApplyStockMovementCommand command, Instant createdAt) {
    Store store = currentStore();
    productStockStore.insertIfAbsent(store.id(), command.productId());
    ProductStockSummary stock =
        productStockStore
            .lockByProduct(store.id(), command.productId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "saldo do produto %s não encontrado após o insert sob demanda"
                            .formatted(command.productId())));
    BigDecimal balanceAfter = stock.quantity().add(command.quantityDelta());
    requireAvailableBalance(store, command, balanceAfter);
    productStockStore.updateQuantity(stock.id(), balanceAfter);
    UUID movementId =
        stockMovementStore.insert(
            new NewStockMovement(
                store.id(),
                command.productId(),
                command.type(),
                command.quantityDelta(),
                balanceAfter,
                command.unitCost(),
                command.referenceType(),
                command.referenceId(),
                command.reason(),
                command.createdByUserId(),
                createdAt));
    return new AppliedStockMovement(
        movementId, command.productId(), stock.quantity(), balanceAfter);
  }

  /**
   * BR-09: loja sem saldo negativo recusa o movimento que deixaria o saldo abaixo de zero; a
   * exceção derruba a transação e nada é gravado. Com a flag ligada não há bloqueio.
   */
  private static void requireAvailableBalance(
      Store store, ApplyStockMovementCommand command, BigDecimal balanceAfter) {
    if (balanceAfter.signum() < 0 && !store.allowNegativeStock()) {
      throw new BusinessException(
          ErrorCode.INSUFFICIENT_STOCK,
          "produto %s ficaria com saldo %s (delta %s) e a loja não permite estoque negativo"
              .formatted(command.productId(), balanceAfter, command.quantityDelta()));
    }
  }

  /** Produto só existe dentro de uma loja; a loja atual vem da configuração, não do comando. */
  private Store currentStore() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode));
  }
}
