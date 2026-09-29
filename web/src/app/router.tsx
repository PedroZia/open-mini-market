import { createBrowserRouter, type RouteObject } from 'react-router';
import { AuthProvider } from '../features/auth/AuthProvider';
import { RequireAuth } from '../features/auth/RequireAuth';
import { LoginPage } from '../features/auth/pages/LoginPage';
import { CategoriesPage } from '../features/categories/pages/CategoriesPage';
import { CustomersPage } from '../features/customers/pages/CustomersPage';
import { DashboardPage } from '../features/dashboard/pages/DashboardPage';
import { ProductsPage } from '../features/products/pages/ProductsPage';
import { RolesPage } from '../features/roles/pages/RolesPage';
import { StockDetailPage } from '../features/stock/pages/StockDetailPage';
import { StockListPage } from '../features/stock/pages/StockListPage';
import { UsersPage } from '../features/users/pages/UsersPage';
import { AppLayout } from './layout/AppLayout';

/**
 * Rotas da retaguarda. Ficam como dados (e não só dentro do router) para o teste montar um
 * `createMemoryRouter` com as mesmas rotas.
 *
 * O `AuthProvider` é a raiz (rota sem path): o login é a única rota pública, fora do layout, e
 * todo o resto vive sob a guarda `RequireAuth` — cada tela nova entra como filha do layout e
 * aparece no `Outlet`.
 */
export const routes: RouteObject[] = [
  {
    element: <AuthProvider />,
    children: [
      { path: '/login', element: <LoginPage /> },
      {
        element: <RequireAuth />,
        children: [
          {
            path: '/',
            element: <AppLayout />,
            children: [
              { index: true, element: <DashboardPage /> },
              { path: 'products', element: <ProductsPage /> },
              { path: 'stock', element: <StockListPage /> },
              { path: 'stock/:productId', element: <StockDetailPage /> },
              { path: 'categories', element: <CategoriesPage /> },
              { path: 'customers', element: <CustomersPage /> },
              { path: 'users', element: <UsersPage /> },
              { path: 'roles', element: <RolesPage /> },
            ],
          },
        ],
      },
    ],
  },
];

export const router = createBrowserRouter(routes);
