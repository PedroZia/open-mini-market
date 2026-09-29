/**
 * Página inicial da retaguarda — placeholder até o dashboard do 1213. O título vive dentro do
 * `<main>` do layout, então a página não traz landmarks próprios de página.
 */
export function DashboardPage() {
  return (
    <section aria-labelledby="titulo-inicio" className="mx-auto max-w-3xl">
      <h1 id="titulo-inicio" className="text-2xl font-semibold">
        Início
      </h1>
      <p className="mt-2 text-ink-muted">
        Retaguarda do minimercado. Escolha um módulo na navegação lateral.
      </p>
    </section>
  );
}
