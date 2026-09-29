import { expect, test } from '@playwright/test';
import { Backend, randomBarcode, uniqueName } from './support/backend';
import { ADMIN, loginThroughUi } from './support/ui';

/**
 * E2E da consulta de venda concluída e da trilha de auditoria (passo 1213): o cenário é montado
 * pela API — produto, estoque, venda, item pelo barcode bruto, pagamento em dinheiro e conclusão —
 * e a conferência é pela tela, do login à lista até o detalhe (1209b).
 *
 * A sessão do `CAIXA-01` é **reaproveitada** quando já existe (a TUI usa o mesmo caixa) e nunca é
 * fechada por aqui; a venda concluída é imutável e fica na trilha — a limpeza desativa só o
 * produto. **Execução local obrigatória:** exige o backend de dev no ar (8081), fora do CI.
 */

/** Caixa do seed de dev: o mesmo que a TUI usa. */
const REGISTER_CODE = 'CAIXA-01';

/** Cenário: preço conhecido, estoque de verdade e dinheiro com troco calculado pelo servidor. */
const PRICE = 12.5;
const RECEIPT_QUANTITY = 5;
const TENDERED = 20;

test('consulta a venda concluída e vê a trilha de auditoria', async ({ page }) => {
  const backend = new Backend();
  const productName = uniqueName('Produto E2E venda');
  const barcode = randomBarcode();

  // 1. Login de reconhecimento só para achar o caixa do seed; a sessão é descartada em seguida.
  await backend.signIn(ADMIN.username, ADMIN.password);
  const register = await backend.requireRegister(REGISTER_CODE);
  await backend.signOut();

  const registerId = register.id;
  if (registerId === undefined) {
    throw new Error(`caixa ${REGISTER_CODE} sem id na resposta`);
  }

  // 2. Sessão de ADMIN vinculada ao caixa (abrir venda exige o vínculo); reaproveita a aberta.
  await backend.signIn(ADMIN.username, ADMIN.password, registerId);
  if ((await backend.currentSession(registerId)) === null) {
    await backend.openSession(registerId, 0);
  }

  // 3. Venda concluída pronta: produto + estoque + item pelo barcode bruto + dinheiro + complete.
  const productId = await backend.createProduct(productName, barcode, PRICE);
  await backend.receiveStock(productId, RECEIPT_QUANTITY, 'carga do E2E (1213)');
  const sale = await backend.openSale();
  const saleId = sale.id;
  const number = sale.number;
  if (saleId === undefined || number === undefined) {
    throw new Error('venda do E2E sem id/número na resposta');
  }
  await backend.addItemByBarcode(saleId, barcode, 1);
  await backend.payCash(saleId, PRICE, TENDERED);
  await backend.completeSale(saleId);

  try {
    await loginThroughUi(page);

    await page.getByRole('link', { name: 'Vendas', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Vendas', exact: true })).toBeVisible();

    // A lista ordena por `created_at desc` (§9.3): a venda do cenário está no topo.
    await page.getByRole('link', { name: String(number), exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/sales/${saleId}$`));
    await expect(
      page.getByRole('heading', { name: `Venda ${number}`, exact: true }),
    ).toBeVisible();

    const items = page.getByRole('table', { name: 'Itens da venda' });
    await expect(items).toContainText(productName);
    await expect(items).toContainText(barcode);
    await expect(items).toContainText('12,50');

    const payments = page.getByRole('table', { name: 'Pagamentos da venda' });
    await expect(payments).toContainText('Dinheiro');
    await expect(payments).toContainText('12,50');
    await expect(payments).toContainText('7,50'); // troco do servidor (BR-05)

    // Trilha (1209b): os eventos `SALE_*` da venda aparecem com o rótulo pt-BR da tela.
    const trail = page.getByRole('region', { name: 'Trilha de auditoria' });
    await expect(trail).toContainText('Venda aberta'); // SALE_CREATED
    await expect(trail).toContainText('Item incluído'); // SALE_ITEM_ADDED
    await expect(trail).toContainText('Pagamento registrado'); // PAYMENT_ADDED
    await expect(trail).toContainText('Venda concluída'); // SALE_COMPLETED
  } finally {
    await backend.disableProduct(productId);
    await backend.signOut();
  }
});
