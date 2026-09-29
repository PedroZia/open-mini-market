import { createBrowserRouter, type RouteObject } from 'react-router';
import { DashboardPage } from '../features/dashboard/pages/DashboardPage';

/**
 * Rotas da retaguarda. Ficam como dados (e não só dentro do router) para o teste montar um
 * `createMemoryRouter` com as mesmas rotas. O layout com navegação lateral entra no 1201b.
 */
export const routes: RouteObject[] = [{ path: '/', element: <DashboardPage /> }];

export const router = createBrowserRouter(routes);
