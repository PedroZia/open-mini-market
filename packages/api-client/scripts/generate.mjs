/**
 * Gera `src/schema.d.ts` a partir do contrato OpenAPI do backend (§9.1 do plano). O default é o
 * dev mode padrão (porta 8080); aponte para outra instância com `OPENAPI_URL`:
 *
 *   npm run generate
 *   OPENAPI_URL="http://localhost:8081/q/openapi?format=json" npm run generate
 *
 * O spec não é commitado — o que fica no repositório é o tipo gerado.
 */
import { writeFile } from 'node:fs/promises';
import openapiTS, { astToString } from 'openapi-typescript';

const defaultUrl = 'http://localhost:8080/q/openapi?format=json';
const source = process.env.OPENAPI_URL ?? defaultUrl;

const ast = await openapiTS(new URL(source));
await writeFile(new URL('../src/schema.d.ts', import.meta.url), astToString(ast), 'utf8');

console.log(`packages/api-client/src/schema.d.ts gerado de ${source}`);
