/**
 * Selo de estoque baixo (1206a): marca visual do `lowStock` que o servidor derivou — produto com
 * mínimo configurado cujo saldo chegou ao mínimo ou ficou abaixo (§5.3). A tela não recalcula nada
 * (BR-12); este componente só desenha o flag da resposta.
 */
export function LowStockBadge() {
  return (
    <span className="rounded-full border border-danger px-2 py-0.5 text-xs font-medium text-danger">
      Estoque baixo
    </span>
  );
}
