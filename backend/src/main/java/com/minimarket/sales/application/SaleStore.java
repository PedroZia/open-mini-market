package com.minimarket.sales.application;

import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.domain.ConflictException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência da venda e dos seus itens (passo 803); o adaptador JPA fica em {@code
 * sales.infrastructure}. Só o agregado e tipos da aplicação atravessam: entidade JPA nunca chega
 * aqui.
 *
 * <p>A venda é o agregado do módulo (§4.1 do plano): quem calcula totais e desconto é o domínio e o
 * adaptador grava o que o agregado diz. Quem abre a transação é o caso de uso (§2.2, regra 6), com
 * o lock de {@link #lockById} valendo até o fim dela.
 */
public interface SaleStore {

  /**
   * Venda pelo id com os itens na ordem de inclusão; vazio para id desconhecido. Venda cancelada
   * (passo 813) é reconstruída como qualquer outra: o agregado rehidrata status, motivo, autor e
   * instante do cancelamento.
   */
  Optional<Sale> findById(UUID id);

  /**
   * Persiste a venda nova (nasce {@code OPEN}) e os itens; o id da venda vem do agregado (UUIDv7 do
   * caso de uso) e o de cada item é gerado pelo adaptador, como nos outros repositórios (§5.1).
   * Venda sem itens é válida — é o estado inicial do passo 805, antes do bipe.
   */
  void insert(Sale sale);

  /**
   * Sincroniza a linha da venda com o estado do agregado: cabeçalho, totais e itens — insere os
   * novos, atualiza os existentes e apaga os que saíram da lista (nada de órfão). O {@code
   * line_number} acompanha a posição do item na lista (1..n). Se outra transação tiver gravado a
   * venda entre a leitura e a gravação, a versão vencida vira {@link ConflictException} com {@code
   * CONCURRENT_MODIFICATION} — o lock otimista do §5.3, nunca 500.
   */
  void update(Sale sale);

  /**
   * Página do histórico da loja, da venda mais nova para a mais antiga ({@code created_at desc}).
   * Todos os filtros são opcionais ({@code null} = sem filtro): período com {@code from} inclusivo
   * e {@code to} exclusivo (§9 do plano), status, sessão de caixa e operador. O caso de uso resolve
   * a página válida; o adaptador só monta a consulta e pagina.
   */
  List<SaleSummary> search(
      Instant from,
      Instant to,
      SaleStatus status,
      UUID cashSessionId,
      UUID operatorUserId,
      int page,
      int size);

  /**
   * Quantas vendas casam com os filtros de {@link #search} (sem ordenação nem paginação): é o
   * {@code totalItems} que o 812 usa para o {@code totalPages} da página.
   */
  long count(Instant from, Instant to, SaleStatus status, UUID cashSessionId, UUID operatorUserId);

  /**
   * Venda pelo id com lock pessimista de escrita na linha ({@code SELECT ... FOR UPDATE}) e os
   * itens: a transação que chama segura o lock até o fim e outra transação que tente a mesma venda
   * espera. É o que serializa duas alterações na mesma venda (o 814 exercita o lock). Vazio para id
   * desconhecido.
   */
  Optional<Sale> lockById(UUID id);

  /**
   * Existe venda {@code OPEN} na sessão de caixa? É o que o fechamento do caixa consulta para
   * recusar fechar com venda em andamento (passo 909).
   */
  boolean existsOpenByCashSession(UUID cashSessionId);
}
