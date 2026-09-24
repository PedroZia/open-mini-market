export interface paths {
    "/api/v1/audit-events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List */
        get: {
            parameters: {
                query?: {
                    action?: string;
                    actorUserId?: string;
                    cashSessionId?: string;
                    entityId?: string;
                    entityType?: string;
                    from?: string;
                    page?: string;
                    size?: string;
                    sort?: string;
                    to?: string;
                };
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["PageResponseAuditEventResponse"];
                    };
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Login */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "X-Client"?: string;
                };
                path?: never;
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["LoginRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["LoginResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Logout */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description Sessão encerrada */
                204: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
                /** @description Not Authorized */
                401: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
                /** @description Not Allowed */
                403: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Me */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CurrentSessionResponse"];
                    };
                };
                /** @description Not Authorized */
                401: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
                /** @description Not Allowed */
                403: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Change Password */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["ChangePasswordRequest"];
                };
            };
            responses: {
                /** @description Senha alterada */
                204: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
                /** @description Not Authorized */
                401: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
                /** @description Not Allowed */
                403: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/sessions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Sessions */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["UserSessionResponse"][];
                    };
                };
                /** @description Not Authorized */
                401: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
                /** @description Not Allowed */
                403: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/sessions/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Revoke Session */
        delete: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description No Content */
                204: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
                /** @description Not Authorized */
                401: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
                /** @description Not Allowed */
                403: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cash-registers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CashRegisterResponse"][];
                    };
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cash-registers/{id}/close": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Close */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "Idempotency-Key"?: string;
                };
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["CloseCashSessionRequest"];
                };
            };
            responses: {
                /** @description Sessão de caixa fechada */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CashSessionDetailResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cash-registers/{id}/current-session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Current Session */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CurrentCashSessionResponse"];
                    };
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cash-registers/{id}/open": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Open */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "Idempotency-Key"?: string;
                };
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["OpenCashSessionRequest"];
                };
            };
            responses: {
                /** @description Sessão de caixa aberta */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CashSessionResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cash-registers/{id}/supplies": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Supply */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "Idempotency-Key"?: string;
                };
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["CashMovementRequest"];
                };
            };
            responses: {
                /** @description Suprimento registrado */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CashMovementResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cash-registers/{id}/withdrawals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Withdraw */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "Idempotency-Key"?: string;
                };
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["CashMovementRequest"];
                };
            };
            responses: {
                /** @description Sangria registrada */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CashMovementResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cash-sessions/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Detail */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CashSessionDetailResponse"];
                    };
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cash-sessions/{id}/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Summary */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CashSessionSummaryResponse"];
                    };
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/categories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CategoryResponse"][];
                    };
                };
            };
        };
        put?: never;
        /** Create */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["CategoryRequest"];
                };
            };
            responses: {
                /** @description Categoria criada */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CategoryResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/categories/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Update */
        put: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["CategoryRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CategoryResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        post?: never;
        /** Delete */
        delete: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description No Content */
                204: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/customers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List */
        get: {
            parameters: {
                query?: {
                    page?: string;
                    search?: string;
                    size?: string;
                };
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["PageResponseCustomerResponse"];
                    };
                };
            };
        };
        put?: never;
        /** Create */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["CustomerRequest"];
                };
            };
            responses: {
                /** @description Cliente criado */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CustomerResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/customers/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CustomerResponse"];
                    };
                };
            };
        };
        /** Update */
        put: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["CustomerRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CustomerResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/customers/{id}/disable": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Disable */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["CustomerResponse"];
                    };
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/meta": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["MetaResponse"];
                    };
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List */
        get: {
            parameters: {
                query?: {
                    active?: string;
                    categoryId?: string;
                    page?: string;
                    search?: string;
                    size?: string;
                    sort?: string;
                };
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["PageResponseProductResponse"];
                    };
                };
            };
        };
        put?: never;
        /** Create */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["CreateProductRequest"];
                };
            };
            responses: {
                /** @description Produto criado */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["ProductResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/barcode/{barcode}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get By Barcode */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    barcode: string;
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["ProductBarcodeResponse"];
                    };
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["ProductResponse"];
                    };
                };
            };
        };
        /** Update */
        put: {
            parameters: {
                query?: never;
                header?: {
                    "If-Match"?: string;
                };
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["UpdateProductRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["ProductResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/{id}/disable": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Disable */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["ProductResponse"];
                    };
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/{id}/enable": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Enable */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["ProductResponse"];
                    };
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/{id}/price": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** Change Price */
        patch: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["ChangeProductPriceRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["ProductResponse"];
                    };
                };
            };
        };
        trace?: never;
    };
    "/api/v1/roles": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["RoleResponse"][];
                    };
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/roles/{code}/permissions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Replace Permissions */
        put: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    code: string;
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["ReplaceRolePermissionsRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["RoleResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sales": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List */
        get: {
            parameters: {
                query?: {
                    cashSessionId?: string;
                    from?: string;
                    operatorUserId?: string;
                    page?: string;
                    size?: string;
                    status?: string;
                    to?: string;
                };
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["PageResponseSaleSummaryResponse"];
                    };
                };
            };
        };
        put?: never;
        /** Create */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "Idempotency-Key"?: string;
                };
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description Venda aberta */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleResponse"];
                    };
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sales/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
                /** @description Not Authorized */
                401: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
                /** @description Not Allowed */
                403: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sales/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Cancel */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "Idempotency-Key"?: string;
                };
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["SaleCancelRequest"];
                };
            };
            responses: {
                /** @description Venda cancelada */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sales/{id}/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Complete */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "Idempotency-Key"?: string;
                };
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description Venda concluída */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sales/{id}/customer": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Link Customer */
        put: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["SaleCustomerRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        post?: never;
        /** Unlink Customer */
        delete: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
            };
        };
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sales/{id}/discount": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Apply Discount */
        put: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["SaleDiscountRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        post?: never;
        /** Remove Discount */
        delete: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
            };
        };
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sales/{id}/items": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Add Item */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["SaleItemRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sales/{id}/items/{itemId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Remove Item */
        delete: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                    itemId: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
            };
        };
        options?: never;
        head?: never;
        /** Change Item Quantity */
        patch: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                    itemId: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["SaleItemQuantityRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
            };
        };
        trace?: never;
    };
    "/api/v1/sales/{id}/payments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Add Payment */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "Idempotency-Key"?: string;
                };
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["SalePaymentRequest"];
                };
            };
            responses: {
                /** @description Pagamento registrado */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sales/{id}/payments/{paymentId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Cancel Payment */
        delete: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                    paymentId: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["SaleDetailResponse"];
                    };
                };
            };
        };
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/stock": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List */
        get: {
            parameters: {
                query?: {
                    lowStock?: string;
                    page?: string;
                    search?: string;
                    size?: string;
                };
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["PageResponseStockItemResponse"];
                    };
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/stock/{productId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    productId: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["StockDetailResponse"];
                    };
                };
            };
        };
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/stock/{productId}/adjustments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Adjust */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "Idempotency-Key"?: string;
                };
                path: {
                    productId: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["StockAdjustmentRequest"];
                };
            };
            responses: {
                /** @description Ajuste registrado */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["StockAdjustmentResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/stock/{productId}/receipts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Receive */
        post: {
            parameters: {
                query?: never;
                header?: {
                    "Idempotency-Key"?: string;
                };
                path: {
                    productId: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["StockReceiptRequest"];
                };
            };
            responses: {
                /** @description Entrada de mercadoria registrada */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["StockReceiptResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List */
        get: {
            parameters: {
                query?: {
                    active?: string;
                    page?: string;
                    search?: string;
                    size?: string;
                    sort?: string;
                };
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["PageResponseUserResponse"];
                    };
                };
            };
        };
        put?: never;
        /** Create */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path?: never;
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["CreateUserRequest"];
                };
            };
            responses: {
                /** @description Usuário criado */
                201: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["UserResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get */
        get: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["UserResponse"];
                    };
                };
            };
        };
        /** Update */
        put: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["UpdateUserRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["UserResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{id}/disable": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Disable */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["UserResponse"];
                    };
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{id}/enable": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Enable */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["UserResponse"];
                    };
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{id}/password-reset": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Reset Password */
        post: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody: {
                content: {
                    "application/json": components["schemas"]["ResetPasswordRequest"];
                };
            };
            responses: {
                /** @description OK */
                200: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content: {
                        "application/json": components["schemas"]["UserResponse"];
                    };
                };
                /** @description Bad Request */
                400: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{id}/sessions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Revoke Sessions */
        delete: {
            parameters: {
                query?: never;
                header?: never;
                path: {
                    id: components["schemas"]["UUID"];
                };
                cookie?: never;
            };
            requestBody?: never;
            responses: {
                /** @description No Content */
                204: {
                    headers: {
                        [name: string]: unknown;
                    };
                    content?: never;
                };
            };
        };
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        AuditEventResponse: {
            /** Format: int64 */
            id?: number;
            occurredAt?: components["schemas"]["Instant"];
            storeId?: components["schemas"]["UUID"];
            actorUserId?: components["schemas"]["UUID"];
            actorUsername?: string;
            authSessionId?: components["schemas"]["UUID"];
            cashSessionId?: components["schemas"]["UUID"];
            cashRegisterId?: components["schemas"]["UUID"];
            action?: string;
            entityType?: string;
            entityId?: components["schemas"]["UUID"];
            source?: components["schemas"]["OperationSource"];
            requestId?: string;
            reason?: string;
            details?: {
                [key: string]: unknown;
            };
            ip?: string;
        };
        CashMovementRequest: {
            amount: number;
            reason: string;
        };
        CashMovementResponse: {
            sessionId?: components["schemas"]["UUID"];
            type?: components["schemas"]["CashMovementType"];
            amount?: number;
            reason?: string;
            expectedBefore?: number;
            expectedAfter?: number;
            aboveExpected?: boolean;
        };
        /** @enum {string} */
        CashMovementType: "OPENING" | "SALE" | "WITHDRAWAL" | "SUPPLY";
        CashRegisterResponse: {
            id?: components["schemas"]["UUID"];
            code?: string;
            name?: string;
            status?: components["schemas"]["CashSessionStatus"];
            operatorName?: string;
        };
        CashSessionDetailResponse: {
            id?: components["schemas"]["UUID"];
            cashRegisterId?: components["schemas"]["UUID"];
            status?: components["schemas"]["CashSessionStatus"];
            openedAt?: components["schemas"]["Instant"];
            openedByUserId?: components["schemas"]["UUID"];
            openingAmount?: number;
            closedAt?: components["schemas"]["Instant"];
            closedByUserId?: components["schemas"]["UUID"];
            countedAmount?: number;
            expectedAmount?: number;
            differenceAmount?: number;
            closingNotes?: string;
        };
        CashSessionResponse: {
            id?: components["schemas"]["UUID"];
            cashRegisterId?: components["schemas"]["UUID"];
            status?: components["schemas"]["CashSessionStatus"];
            openedAt?: components["schemas"]["Instant"];
            openedByUserId?: components["schemas"]["UUID"];
            openingAmount?: number;
        };
        /** @enum {string} */
        CashSessionStatus: "OPEN" | "CLOSED";
        CashSessionSummaryResponse: {
            sessionId?: components["schemas"]["UUID"];
            status?: components["schemas"]["CashSessionStatus"];
            openingAmount?: number;
            expectedAmount?: number;
            countedAmount?: number;
            differenceAmount?: number;
            totalsByType?: {
                [key: string]: number;
            };
            paymentsByMethod?: {
                [key: string]: number;
            };
        };
        CategoryRequest: {
            name: string;
            parentId?: components["schemas"]["UUID"];
            /** Format: int32 */
            sortOrder?: number;
        };
        CategoryResponse: {
            id?: components["schemas"]["UUID"];
            name?: string;
            parentId?: components["schemas"]["UUID"];
            active?: boolean;
            /** Format: int32 */
            sortOrder?: number;
        };
        ChangePasswordRequest: {
            currentPassword: string;
            newPassword: string;
        };
        ChangeProductPriceRequest: {
            price: number;
            reason: string;
        };
        CloseCashSessionRequest: {
            countedAmount: number;
            notes?: string;
        };
        CreateProductRequest: {
            name: string;
            barcode?: string;
            internalCode?: string;
            description?: string;
            categoryId?: components["schemas"]["UUID"];
            unit: string;
            price: number;
            minQuantity?: number;
        };
        CreateUserRequest: {
            username: string;
            displayName: string;
            password: string;
            roleCodes?: string[];
        };
        CurrentCashSessionResponse: {
            sessionId?: components["schemas"]["UUID"];
            cashRegisterId?: components["schemas"]["UUID"];
            status?: components["schemas"]["CashSessionStatus"];
            openedAt?: components["schemas"]["Instant"];
            openedByUserId?: components["schemas"]["UUID"];
            openingAmount?: number;
            expectedAmount?: number;
            totalsByType?: {
                [key: string]: number;
            };
        };
        CurrentSessionResponse: {
            user?: components["schemas"]["LoginUser"];
            roles?: string[];
            permissions?: string[];
            store?: components["schemas"]["StoreRef"];
            cashRegisterId?: components["schemas"]["UUID"];
            client?: components["schemas"]["SessionClient"];
            expiresAt?: components["schemas"]["Instant"];
            lastSeenAt?: components["schemas"]["Instant"];
        };
        CustomerRequest: {
            name: string;
            taxId?: string;
            phone?: string;
            email?: string;
            notes?: string;
        };
        CustomerResponse: {
            id?: components["schemas"]["UUID"];
            name?: string;
            taxId?: string;
            phone?: string;
            email?: string;
            notes?: string;
            active?: boolean;
            /** Format: int64 */
            version?: number;
            createdAt?: components["schemas"]["Instant"];
            updatedAt?: components["schemas"]["Instant"];
        };
        /** @enum {string} */
        DiscountType: "VALUE" | "PERCENT";
        /**
         * Format: date-time
         * @example 2022-03-10T16:15:50Z
         */
        Instant: string;
        LoginRequest: {
            username: string;
            password: string;
            cashRegisterId?: components["schemas"]["UUID"];
        };
        LoginResponse: {
            token?: string;
            expiresAt?: components["schemas"]["Instant"];
            user?: components["schemas"]["LoginUser"];
            roles?: string[];
            permissions?: string[];
            mustChangePassword?: boolean;
        };
        LoginUser: {
            id?: components["schemas"]["UUID"];
            username?: string;
            displayName?: string;
        };
        MetaResponse: {
            apiVersion?: string;
            storeCode?: string;
            allowNegativeStock?: boolean;
            maxDiscountPercent?: number;
            serverTime?: components["schemas"]["Instant"];
        };
        OpenCashSessionRequest: {
            openingAmount: number;
        };
        /** @enum {string} */
        OperationSource: "API" | "TUI" | "WEB" | "SYSTEM";
        PageResponseAuditEventResponse: {
            items?: components["schemas"]["AuditEventResponse"][];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalItems?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        PageResponseCustomerResponse: {
            items?: components["schemas"]["CustomerResponse"][];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalItems?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        PageResponseProductResponse: {
            items?: components["schemas"]["ProductResponse"][];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalItems?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        PageResponseSaleSummaryResponse: {
            items?: components["schemas"]["SaleSummaryResponse"][];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalItems?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        PageResponseStockItemResponse: {
            items?: components["schemas"]["StockItemResponse"][];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalItems?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        PageResponseUserResponse: {
            items?: components["schemas"]["UserResponse"][];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            totalItems?: number;
            /** Format: int32 */
            totalPages?: number;
        };
        /** @enum {string} */
        PaymentMethod: "CASH" | "PIX" | "DEBIT" | "CREDIT" | "VOUCHER";
        PaymentResponse: {
            id?: components["schemas"]["UUID"];
            method?: components["schemas"]["PaymentMethod"];
            amount?: number;
            tenderedAmount?: number;
            changeAmount?: number;
            status?: components["schemas"]["PaymentStatus"];
            createdByUserId?: components["schemas"]["UUID"];
            createdAt?: components["schemas"]["Instant"];
            cancelledAt?: components["schemas"]["Instant"];
        };
        /** @enum {string} */
        PaymentStatus: "APPROVED" | "CANCELLED";
        ProductBarcodeResponse: {
            id?: components["schemas"]["UUID"];
            barcode?: string;
            name?: string;
            price?: number;
            unit?: string;
            quantity?: number;
        };
        ProductResponse: {
            id?: components["schemas"]["UUID"];
            name?: string;
            barcode?: string;
            internalCode?: string;
            description?: string;
            categoryId?: components["schemas"]["UUID"];
            unit?: string;
            price?: number;
            minQuantity?: number;
            active?: boolean;
            /** Format: int64 */
            version?: number;
            createdAt?: components["schemas"]["Instant"];
            updatedAt?: components["schemas"]["Instant"];
        };
        ReplaceRolePermissionsRequest: {
            permissions: string[];
        };
        ResetPasswordRequest: {
            newPassword: string;
        };
        RoleResponse: {
            code?: string;
            name?: string;
            description?: string;
            system?: boolean;
            permissions?: string[];
        };
        SaleCancelRequest: {
            reason: string;
        };
        SaleCustomerRequest: {
            customerId: components["schemas"]["UUID"];
        };
        SaleDetailResponse: {
            id?: components["schemas"]["UUID"];
            /** Format: int64 */
            number?: number;
            status?: components["schemas"]["SaleStatus"];
            cashSessionId?: components["schemas"]["UUID"];
            cashRegisterId?: components["schemas"]["UUID"];
            operatorUserId?: components["schemas"]["UUID"];
            customerId?: components["schemas"]["UUID"];
            subtotal?: number;
            discountType?: components["schemas"]["DiscountType"];
            discountValue?: number;
            discountReason?: string;
            discountAmount?: number;
            total?: number;
            paidAmount?: number;
            changeAmount?: number;
            /** Format: int32 */
            itemCount?: number;
            createdAt?: components["schemas"]["Instant"];
            completedAt?: components["schemas"]["Instant"];
            cancelReason?: string;
            cancelledByUserId?: components["schemas"]["UUID"];
            cancelledAt?: components["schemas"]["Instant"];
            items?: components["schemas"]["SaleItemResponse"][];
            payments?: components["schemas"]["PaymentResponse"][];
        };
        SaleDiscountRequest: {
            type: components["schemas"]["DiscountType"];
            value: number;
            reason: string;
        };
        SaleItemQuantityRequest: {
            quantity: number;
        };
        SaleItemRequest: {
            barcode?: string;
            productId?: components["schemas"]["UUID"];
            quantity: number;
        };
        SaleItemResponse: {
            productId?: components["schemas"]["UUID"];
            barcode?: string;
            name?: string;
            unit?: string;
            unitPrice?: number;
            quantity?: number;
            lineTotal?: number;
        };
        SalePaymentRequest: {
            method: components["schemas"]["PaymentMethod"];
            amount: number;
            tenderedAmount?: number;
        };
        SaleResponse: {
            id?: components["schemas"]["UUID"];
            /** Format: int64 */
            number?: number;
            status?: components["schemas"]["SaleStatus"];
            cashSessionId?: components["schemas"]["UUID"];
            cashRegisterId?: components["schemas"]["UUID"];
            operatorUserId?: components["schemas"]["UUID"];
            subtotal?: number;
            discountAmount?: number;
            total?: number;
            /** Format: int32 */
            itemCount?: number;
            createdAt?: components["schemas"]["Instant"];
        };
        /** @enum {string} */
        SaleStatus: "OPEN" | "COMPLETED" | "CANCELLED";
        SaleSummaryResponse: {
            id?: components["schemas"]["UUID"];
            /** Format: int64 */
            number?: number;
            status?: components["schemas"]["SaleStatus"];
            cashSessionId?: components["schemas"]["UUID"];
            cashRegisterId?: components["schemas"]["UUID"];
            operatorUserId?: components["schemas"]["UUID"];
            customerId?: components["schemas"]["UUID"];
            subtotal?: number;
            discountAmount?: number;
            total?: number;
            /** Format: int32 */
            itemCount?: number;
            createdAt?: components["schemas"]["Instant"];
            completedAt?: components["schemas"]["Instant"];
        };
        /** @enum {string} */
        SessionClient: "TUI" | "WEB";
        StockAdjustmentRequest: {
            quantityDelta: number;
            reason: string;
        };
        StockAdjustmentResponse: {
            movementId?: components["schemas"]["UUID"];
            productId?: components["schemas"]["UUID"];
            quantityDelta?: number;
            balanceBefore?: number;
            balanceAfter?: number;
        };
        StockDetailResponse: {
            productId?: components["schemas"]["UUID"];
            name?: string;
            barcode?: string;
            unit?: string;
            quantity?: number;
            minQuantity?: number;
            lowStock?: boolean;
            movements?: components["schemas"]["StockMovementResponse"][];
        };
        StockItemResponse: {
            productId?: components["schemas"]["UUID"];
            name?: string;
            barcode?: string;
            unit?: string;
            quantity?: number;
            minQuantity?: number;
            lowStock?: boolean;
        };
        StockMovementResponse: {
            id?: components["schemas"]["UUID"];
            type?: components["schemas"]["StockMovementType"];
            quantityDelta?: number;
            balanceAfter?: number;
            unitCost?: number;
            referenceType?: string;
            referenceId?: components["schemas"]["UUID"];
            reason?: string;
            createdByUserId?: components["schemas"]["UUID"];
            createdAt?: components["schemas"]["Instant"];
        };
        /** @enum {string} */
        StockMovementType: "INITIAL" | "PURCHASE_IN" | "SALE_OUT" | "RETURN_IN" | "ADJUSTMENT" | "LOSS";
        StockReceiptRequest: {
            quantity: number;
            unitCost?: number;
            reason?: string;
        };
        StockReceiptResponse: {
            movementId?: components["schemas"]["UUID"];
            productId?: components["schemas"]["UUID"];
            quantity?: number;
            unitCost?: number;
            balanceBefore?: number;
            balanceAfter?: number;
        };
        StoreRef: {
            code?: string;
            name?: string;
        };
        /** Format: uuid */
        UUID: string;
        UpdateProductRequest: {
            name: string;
            internalCode?: string;
            categoryId?: components["schemas"]["UUID"];
            unit: string;
            description?: string;
            minQuantity?: number;
        };
        UpdateUserRequest: {
            displayName: string;
            roleCodes: string[];
        };
        UserResponse: {
            id?: components["schemas"]["UUID"];
            username?: string;
            displayName?: string;
            roles?: string[];
            status?: string;
            mustChangePassword?: boolean;
        };
        UserSessionResponse: {
            id?: components["schemas"]["UUID"];
            client?: components["schemas"]["SessionClient"];
            ip?: string;
            userAgent?: string;
            createdAt?: components["schemas"]["Instant"];
            lastSeenAt?: components["schemas"]["Instant"];
            expiresAt?: components["schemas"]["Instant"];
            current?: boolean;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export type operations = Record<string, never>;
