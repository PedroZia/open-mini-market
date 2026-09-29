import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DetailsView } from './DetailsView';

/**
 * Visão dos `details` (1211b) com dados como o servidor grava: mapa livre, com objetos e listas
 * aninhados, booleanos e nulos. O que a tela promete é legibilidade — nenhum valor é recalculado,
 * formatado como dinheiro ou escondido.
 */

describe('DetailsView — detalhes do evento', () => {
  it('mostra objeto e lista aninhados sem quebrar', () => {
    render(
      <DetailsView
        details={{
          saleId: '0198f9c4',
          items: [
            { name: 'Arroz 5kg', quantity: 2 },
            { name: 'Feijão 1kg', quantity: 1 },
          ],
          paymentsByMethod: { CASH: 30.5 },
          paid: true,
          customer: null,
        }}
      />,
    );

    // Chave sem tradução sai como o servidor a escreveu; a lista mantém o índice de cada item.
    expect(screen.getByText('saleId')).toBeInTheDocument();
    expect(screen.getByText('0198f9c4')).toBeInTheDocument();
    expect(screen.getByText('items')).toBeInTheDocument();
    expect(screen.getByText('[0]')).toBeInTheDocument();
    expect(screen.getByText('[1]')).toBeInTheDocument();

    // Chaves recorrentes ganham rótulo pt-BR em qualquer profundidade.
    expect(screen.getAllByText('Nome')).toHaveLength(2);
    expect(screen.getAllByText('Quantidade')).toHaveLength(2);
    expect(screen.getByText('Arroz 5kg')).toBeInTheDocument();
    expect(screen.getByText('Feijão 1kg')).toBeInTheDocument();

    // Objeto dentro de objeto continua navegável.
    expect(screen.getByText('paymentsByMethod')).toBeInTheDocument();
    expect(screen.getByText('CASH')).toBeInTheDocument();
    expect(screen.getByText('30.5')).toBeInTheDocument();

    // Folhas legíveis: booleano e nulo não somem.
    expect(screen.getByText('sim')).toBeInTheDocument();
    expect(screen.getByText('nulo')).toBeInTheDocument();
  });

  it('põe o antes e o depois lado a lado, sem perder o resto do mapa', () => {
    render(
      <DetailsView
        details={{ before: { total: 120 }, after: { total: 140 }, reason: 'Troca de item' }}
      />,
    );

    const before = screen.getByRole('region', { name: 'Antes' });
    const after = screen.getByRole('region', { name: 'Depois' });
    expect(within(before).getByText('Total')).toBeInTheDocument();
    expect(within(before).getByText('120')).toBeInTheDocument();
    expect(within(after).getByText('Total')).toBeInTheDocument();
    expect(within(after).getByText('140')).toBeInTheDocument();

    // As demais chaves do mapa continuam visíveis fora do par.
    expect(screen.getByRole('region', { name: 'Demais dados' })).toBeInTheDocument();
    expect(screen.getByText('Motivo')).toBeInTheDocument();
    expect(screen.getByText('Troca de item')).toBeInTheDocument();
  });

  it('avisa quando só um lado do antes/depois existe', () => {
    render(<DetailsView details={{ after: { quantity: 3 } }} />);

    const before = screen.getByRole('region', { name: 'Antes' });
    expect(within(before).getByText('Sem registro')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Depois' })).getByText('3')).toBeInTheDocument();
  });

  it('mostra "Sem detalhes" quando o evento não traz o mapa', () => {
    render(<DetailsView />);

    expect(screen.getByText('Sem detalhes')).toBeInTheDocument();
  });

  it('trata mapa vazio como evento sem detalhes', () => {
    render(<DetailsView details={{}} />);

    expect(screen.getByText('Sem detalhes')).toBeInTheDocument();
  });
});
