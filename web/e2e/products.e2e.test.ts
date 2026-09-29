import { expect, test } from '@playwright/test';
import { Backend, randomBarcode, uniqueName } from './support/backend';
import { ADMIN, loginThroughUi } from './support/ui';

/**
 * E2E do cadastro de produto (passo 1213): login pela tela, cadastro no modal de "Novo produto" e
 * conferência na lista pela busca do servidor. O cenário é limpo ao final desativando o produto
 * pela API — a tela não apaga cadastro, e o E2E não inventa uma ação que não existe.
 *
 * **Execução local obrigatória:** grava no banco de dev e exige o backend no ar (8081) — por isso
 * não roda no `npm test` nem no CI; os comandos estão no `web/README.md`.
 */

/** Preço digitado na tela (pt-BR, como o campo pede); a lista mostra o mesmo valor formatado. */
const PRICE_INPUT = '7,50';
const PRICE_TEXT = '7,50';

test('cadastra produto e confere na lista', async ({ page }) => {
  const backend = new Backend();
  const name = uniqueName('Produto E2E');
  const barcode = randomBarcode();

  // Token do fixture só para a limpeza; a sessão do navegador nasce no login da UI.
  await backend.signIn(ADMIN.username, ADMIN.password);

  try {
    await loginThroughUi(page);

    await page.getByRole('link', { name: 'Produtos', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Produtos', exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'Novo produto', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: 'Novo produto', exact: true })).toBeVisible();

    await dialog.getByLabel('Nome', { exact: true }).fill(name);
    await dialog.getByLabel('Código de barras', { exact: true }).fill(barcode);
    await dialog.getByLabel('Unidade', { exact: true }).selectOption('UN');
    await dialog.getByLabel('Preço (R$)', { exact: true }).fill(PRICE_INPUT);
    await dialog.getByRole('button', { name: 'Salvar', exact: true }).click();
    await expect(dialog).toBeHidden();

    // A busca é do servidor e cobre o nome: o produto recém-criado volta na lista com os dados
    // que ele gravou, e a paginação confirma que a busca isolou só ele.
    await page.getByLabel('Buscar', { exact: true }).fill(name);
    const row = page.getByRole('row').filter({ hasText: name });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText(barcode);
    await expect(row).toContainText(PRICE_TEXT);
    await expect(row).toContainText('Ativo');
    await expect(
      page.getByRole('navigation', { name: 'Paginação de Produtos' }),
    ).toContainText('1 item');
  } finally {
    const productId = await backend.findProductId(name);
    if (productId !== null) {
      await backend.disableProduct(productId);
    }
    await backend.signOut();
  }
});
