### BUG 1 — Inactive product list includes active products
Symptom: The inactive-product view also displays products that are still active.
Reproduction: Sign in as admin. Create a fresh category with `POST /api/v1/categories` using a unique `code`, a `name`, and `active:true`; note its ID. Create two products in that category with unique `sku` values, `name`, `unit:"UNIT"`, `minimumStock:0`, and respectively `active:true` and `active:false`. Request `GET /api/v1/products?categoryId=<categoryId>&active=false`.
Expected: HTTP 200, only the inactive product in `content`, and `totalElements:1`.
Actual: HTTP 200, both products in `content`, and `totalElements:2`.
Useful endpoint / starting point: `GET /api/v1/products`; compare each returned product's `active` value.

### BUG 2 — A valid short contact message is rejected
Symptom: A contact submission at the supported ten-character minimum cannot be sent.
Reproduction: Without authentication, send `POST /api/v1/contact` with `{"name":"Training visitor","email":"visitor@example.test","subject":"Delivery","message":"Need boxes"}`. The message has exactly ten characters, including its space.
Expected: HTTP 201 with an acknowledgement containing an ID and status `NEW`.
Actual: HTTP 400; no acknowledgement or contact record is created.
Useful endpoint / starting point: `POST /api/v1/contact`; inspect the validation response.

### BUG 3 — Supplier phone number changes in API responses
Symptom: A supplier's returned phone number differs from the submitted phone number.
Reproduction: Sign in as admin. Send `POST /api/v1/suppliers` with a unique `code`, `name:"Training supplier"`, `email:"orders@example.test"`, `phone:"+34 910 123 456"`, and `active:true`. Note the returned ID, then request `GET /api/v1/suppliers/<id>`.
Expected: The response preserves `email:"orders@example.test"` and `phone:"+34 910 123 456"`.
Actual: The response's `phone` contains `orders@example.test`. The creation response has the same symptom.
Useful endpoint / starting point: `POST /api/v1/suppliers` and `GET /api/v1/suppliers/{id}`.

### BUG 4 — Warehouse location pages repeat an entry
Symptom: Moving to the next location page repeats an entry and leaves another location out.
Reproduction: Sign in as admin. Create a fresh warehouse using `POST /api/v1/warehouses` with a unique `code`, `name`, and `active:true`; note its ID. It has a DEFAULT location. Add three locations with `POST /api/v1/warehouses/<id>/locations`, using codes `AISLE-A`, `AISLE-B`, `AISLE-C` and `active:true`. Request `/api/v1/warehouses/<id>/locations?page=0&size=2`, then the same URL with `page=1`.
Expected: Both responses report `totalElements:4`. Page 0 contains DEFAULT and AISLE-A; page 1 contains AISLE-B and AISLE-C, without repeated IDs.
Actual: Page 1 contains AISLE-A and AISLE-B. AISLE-A repeats, and AISLE-C is absent from the two pages.
Useful endpoint / starting point: `GET /api/v1/warehouses/{id}/locations`; compare IDs across pages.

### BUG 5 — Approved purchase cannot be cancelled before receipt
Symptom: An approved purchase that has not been received cannot be cancelled.
Reproduction: Sign in as manager. Discover an active supplier, product, and warehouse within your scope. Create an order with `POST /api/v1/purchase-orders`, supplying `supplierId`, `warehouseId`, and a unique `orderNumber`. Add a line with `POST /api/v1/purchase-orders/<id>/lines` and `{"productId":<productId>,"quantity":3,"unitCost":2.5}`. POST its `/submit`, then `/approve`, then `/cancel`, with no request bodies. Do not receive the order.
Expected: Cancellation returns HTTP 200 and status `CANCELLED`; stock stays unchanged.
Actual: Cancellation returns HTTP 409. A subsequent GET still reports `APPROVED`.
Useful endpoint / starting point: `POST /api/v1/purchase-orders/{id}/cancel` and `GET /api/v1/purchase-orders/{id}`.
