import { expect, type Page } from '@playwright/test';

/**
 * Passos de UI compartilhados pelos specs do E2E da retaguarda (passo 1213) — seletores
 * acessíveis, como a tela os expõe: `label` nos campos e papel nos botões/links.
 */

/** ADMIN inicial do `%dev` (`ADMIN_INITIAL_PASSWORD`, passo 115). */
export const ADMIN = { username: 'admin', password: 'admin123' } as const;

/**
 * Login pela tela real (`/login`, 1202): preenche usuário/senha e espera o layout da guarda. A
 * sessão fica no `sessionStorage` do contexto do teste — cada spec começa anônimo.
 */
export async function loginThroughUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Usuário', { exact: true }).fill(ADMIN.username);
  await page.getByLabel('Senha', { exact: true }).fill(ADMIN.password);
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();

  // A guarda só monta o layout com a sessão criada; "Início" é o destino padrão do login.
  await expect(page.getByRole('link', { name: 'Início', exact: true })).toBeVisible();
}
