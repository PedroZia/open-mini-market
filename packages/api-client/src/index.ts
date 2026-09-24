export { createApiClient } from './client';
export type { ApiClient, ApiClientOptions, RequestOptions, TokenProvider } from './client';
export { ApiError, ApiNetworkError, ApiTimeoutError } from './errors';
export type { ApiFieldError, ProblemDetails } from './errors';
// Contrato do backend (§9.1 do plano): `paths`/`components`/`operations` saem do OpenAPI; nenhum
// tipo de request/response é escrito à mão no cliente.
export type * from './schema';
