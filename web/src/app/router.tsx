import { createBrowserRouter, type RouteObject } from 'react-router';
import { DashboardPage } from '../features/dashboard/pages/DashboardPage';
import { AppLayout } from './layout/AppLayout';

/**
 * Rotas da retaguarda. Ficam como dados (e não só dentro do router) para o teste montar um
 * `createMemoryRouter` com as mesmas rotas. O layout é a casca única: cada tela nova vira filha
 * dele e aparece no `Outlet`.
 */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    children: [{ index: true, element: <DashboardPage /> }],
  },
];

export const router = createBrowserRouter(routes);
