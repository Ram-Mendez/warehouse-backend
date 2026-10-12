# API reference

## Swagger quick start

Open **http://localhost:8080/swagger-ui/index.html**. Endpoints expose validated request DTOs, permissions and error responses.

1. Execute `POST /api/v1/auth/login`:

   ```json
   {"username":"admin","password":"Admin-local-2026!"}
   ```

2. Copy `accessToken`. Click **Authorize**, paste just the token, and authorize the `bearerAuth` scheme. Store `refreshToken` separately if trying refresh.
3. GET `/warehouses`, `/products?sku=BOX-001`, `/categories`, and `/suppliers`. Note returned warehouse/product IDs.
4. Receive 10 units using the discovered IDs:

   ```json
   {"warehouseId":1,"productId":1,"quantity":10,"reason":"Initial delivery"}
   ```

   Replace example IDs. Omitting `locationId` uses that warehouse's DEFAULT bin.

5. GET `/warehouses/{warehouseId}/inventory/{productId}`; quantity should rise by 10.
6. POST `/transfers`:

   ```json
   {"sourceWarehouseId":1,"destinationWarehouseId":2,"productId":1,"quantity":3,"reason":"Replenish South"}
   ```

7. POST `/movements/issue` with source warehouse, product, quantity 2 and a reason. Inspect both balances.
8. GET `/movements?productId=...`, `/audit/stock-movements`, and `/audit/events`.
9. To create a product, POST `/products` with `sku`, `name`, `unit`, `minimumStock:0`, `active:true`, and optional category/supplier IDs/unit cost. GET `/products/{id}/suppliers` shows linked costs. PUT requires the current product `version` from GET.
10. For purchasing: POST order (`supplierId`, `warehouseId`, `orderNumber`), POST its `/lines` (`productId`, `quantity`, optional `unitCost`), POST `/submit`, optionally `/approve`, then `/receive`. Receiving again returns 409.

Refresh returns new access **and** refresh tokens; update both. Reusing the old refresh token deliberately revokes the session. Use the worker account to demonstrate South warehouse 403.

## Endpoint map

All business routes start with `/api/v1`. Permission names below carry the `PERM_` prefix. Warehouse reads/writes also enforce scope; lists are filtered to accessible resources.

| Module | Endpoints | Permission |
| --- | --- | --- |
| Auth | POST `/auth/login`, `/auth/refresh`; POST `/auth/logout`; GET `/auth/me` | First two public; others JWT |
| Categories | GET `/categories`, `/{id}`; POST `/categories`; PUT `/{id}` | CATEGORY_READ / CATEGORY_WRITE |
| Suppliers | GET `/suppliers`, `/{id}`; POST `/suppliers`; PUT `/{id}` | SUPPLIER_READ / SUPPLIER_WRITE |
| Warehouses | GET `/warehouses`, `/{id}`; POST `/warehouses`; PUT `/{id}` | WAREHOUSE_READ / WAREHOUSE_MANAGE |
| Locations | GET `/warehouses/{w}/locations`, `/{id}`; POST locations; PUT `/{id}` | WAREHOUSE_READ / WAREHOUSE_MANAGE |
| Products | GET `/products`, `/{id}`, `/{id}/suppliers`; POST products; PUT `/{id}` | PRODUCT_READ / PRODUCT_WRITE |
| Inventory | GET `/inventory`; GET `/warehouses/{w}/inventory`, `/{productId}` | INVENTORY_READ |
| Stock | POST `/movements/receipt`, `/return`; POST `/issue`; POST `/adjustment` | STOCK_RECEIVE / STOCK_ISSUE / INVENTORY_ADJUST |
| Transfers | POST `/transfers` | STOCK_TRANSFER + both warehouse scopes |
| Movements | GET `/movements`, `/{id}`; POST `/{id}/compensate` | MOVEMENT_READ / INVENTORY_ADJUST |
| Purchases | POST/GET `/purchase-orders`; GET `/{id}`; POST `/{id}/lines`, `/submit`, `/cancel` | PURCHASE_CREATE / PURCHASE_READ |
| Purchase approval/receipt | POST `/purchase-orders/{id}/approve`, `/receive` | PURCHASE_APPROVE / PURCHASE_RECEIVE |
| Contact | POST `/contact`; GET `/contact`, `/{id}`; PATCH `/{id}/status` | Public submission; CONTACT_READ / CONTACT_MANAGE |
| Audit | GET `/audit/stock-movements`, `/audit/movements/{id}`, `/audit/events` | AUDIT_READ + relevant scopes; user/contact events also require their management/read permissions |
| Admin | GET/POST `/admin/users`; GET/PATCH `/{id}`; PUT `/{id}/roles`, `/{id}/warehouse-scopes`; GET `/admin/roles` | USER_MANAGE |

List parameters: `page=0`, `size=20` (maximum 100). Product filters: `search`, exact `sku`, `categoryId`, `supplierId`, `active`; sorts: `id`, `name`, `sku`, `createdAt` (newest first). Search treats `%`, `_`, and backslash literally. Inventory filters warehouse/product; movement filters warehouse/product/type; purchase filters warehouse/status; audit filters movement ID or entity type/ID. Purchase lists return summaries; detail includes lines.

PUT catalog endpoints replace supplied state; `active:false` deactivates records. Hard deletion is not exposed. Contact status: NEW, IN_PROGRESS, RESOLVED, SPAM. Purchase workflow: DRAFT → SUBMITTED → optional APPROVED → RECEIVED; cancellable before receipt. Partial receipt is intentionally not implemented.

Errors use ProblemDetail with HTTP status, safe detail, path, timestamp and correlation ID. Validation/malformed input: 400; authentication: 401; permission/scope: 403; absent resource: 404; duplicates, insufficient stock, state/version/lock conflicts: 409. Unexpected failures: 500 without stack traces, SQL or credentials in the response.

