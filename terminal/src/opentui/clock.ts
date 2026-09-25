import { useEffect, useState } from "react"

/** Um tique por segundo: é o passo do relógio do cabeçalho no app. */
export const CLOCK_INTERVAL_MS = 1000

/**
 * Relógio vivo da UI nova: devolve a hora corrente e a renova a cada `intervalMs`.
 *
 * A Ink passava `now={new Date()}` no render do shell — a hora congelava até o próximo render, que
 * só vinha de uma ação do operador —, então o relógio do cabeçalho mentia com a tela parada. O timer
 * mora no shell (`App` chama o hook) e a hora entra na tela por prop, o que mantém o desenho
 * determinístico no teste: quem monta a `SaleScreen` decide a hora.
 *
 * O intervalo é limpo no unmount (o `renderer.destroy()` do app desmonta a árvore), então nenhum
 * timer vaza entre testes nem depois do ESC.
 */
export function useClock(intervalMs: number = CLOCK_INTERVAL_MS): Date {
  const [now, setNow] = useState(() => new Date())

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), intervalMs)
    return () => clearInterval(timer)
  }, [intervalMs])

  return now
}
