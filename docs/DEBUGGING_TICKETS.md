# Incidencias de entrenamiento — SQL Debugging 1
Rama: `training/sql-debugging-1`. Cinco incidencias independientes sobre operaciones existentes.
El orden aumenta la dificultad; empieza por el Ticket 1. No hay soluciones ni tests resolutivos en este documento.
## Preparación común
- Usa Swagger y una cuenta de desarrollo con permisos de administración. Para inventario, la cuenta debe tener acceso al almacén elegido; -
- crear un almacén con esa cuenta le concede acceso. -
- Localiza las operaciones por su recurso y finalidad. Anota la ruta, los parámetros, el cuerpo enviado y la respuesta real. -
- Crea datos nuevos con un sufijo único en códigos y SKU. Guarda los IDs devueltos: los ejemplos no presuponen IDs concretos ni dependen de los otros tickets. -
- Las actualizaciones de catálogo reciben el cuerpo completo. Conserva los campos que no quieras cambiar; cuando actualices productos, usa su versión actual.
- Hay una base de práctica aislada en el PostgreSQL de Compose: `warehouse_sql_debugging_1`. Contiene únicamente datos de desarrollo y verificaciones de esta preparación. Puedes crear allí tus propios escenarios sin alterar la base `warehouse`. -- 
Arranque desde PowerShell en la raíz del proyecto:
```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:DB_URL = 'jdbc:postgresql://localhost:5434/warehouse_sql_debugging_1'
.\mvnw.cmd spring-boot:run
```
Swagger: `http://localhost:8080/swagger-ui/index.html` (puerto predeterminado). Las cuentas de desarrollo están descritas en README.
La aplicación usa JPA/Hibernate. Puedes contrastar sus resultados escribiendo SQL directamente en PostgreSQL; no necesitas añadir una capa de persistencia ni un RowMapper.


## Ticket 1 — El catálogo devuelve dos artículos al buscar una referencia exacta
**Contexto de negocio.** Compras busca un artículo por su referencia interna para evitar seleccionar un material parecido. 
Se ha detectado que una referencia que contiene un guion bajo devuelve también otro artículo con una referencia distinta.
**Pasos para reproducir.**
1. Elige una categoría existente y anota su ID.    ---- 2 y 35
2. Crea dos productos activos de esa categoría, con unidad `UNIT` y stock mínimo `0`. Usa un prefijo nuevo; por ejemplo, SKU `SQLD1_A` y `SQLD1XA`, con nombres `Caja estándar` y `Caja reforzada`. No es necesario asignarles proveedor ni registrar existencias.
3. IDS 147 y 148
3. Localiza la consulta del catálogo y envía el parámetro **`sku=SQLD1_A`**. Deja `search`, `supplierId`, `categoryId` y `active` sin enviar; utiliza página `0`, tamaño `20` y orden `id`. --
4. Revisa tanto los elementos devueltos como `totalElements`. Repite con `sku=SQLD1XA` como comparación.
5. {
   "content": [
   {
   "id": 135,
   "sku": "SQLD1_A",
   "barcode": "SQLD1-001",
   "name": "Caja estándar",
   "description": "Caja de cartón estándar",
   "categoryId": 2,
   "unit": "UNIT",
   "minimumStock": 0,
   "active": true,
   "version": 0,
   "createdAt": "2026-10-12T06:12:02.198186Z",
   "updatedAt": "2026-10-12T06:12:02.198186Z"
   }
   ],
   "totalElements": 1,
   "page": 0,
   "size": 20
   }
   {
   "content": [
   {
   "id": 136,
   "sku": "SQLD1XA",
   "barcode": "SQLD1-002",
   "name": "Caja reforzada",
   "description": "Caja de cartón reforzada",
   "categoryId": 2,
   "unit": "UNIT",
   "minimumStock": 0,
   "active": true,
   "version": 0,
   "createdAt": "2026-10-12T06:12:09.982183Z",
   "updatedAt": "2026-10-12T06:12:09.982183Z"
   }
   ],
   "totalElements": 1,
   "page": 0,
   "size": 20
   }
**Comportamiento esperado.** La búsqueda exacta de `SQLD1_A` devuelve únicamente ese producto y `totalElements=1`.
**Comportamiento observado.** La búsqueda de `SQLD1_A` devuelve los dos productos y `totalElements=2`. La búsqueda de `SQLD1XA` devuelve únicamente ese segundo producto. Ambas peticiones responden HTTP `200`.
**Terminología.**
- **SKU:** referencia interna que identifica un artículo del catálogo.
- **Filtro exacto:** selección de un valor completo, incluyendo sus caracteres.
- **Parámetro HTTP:** dato enviado en la URL para concretar una consulta; aquí se utiliza `sku`.
- **`content` / `totalElements`:** elementos de la página y número total de coincidencias, respectivamente.
## Ticket 2 — Un artículo recién registrado desaparece de las consultas del catálogo
**Contexto de negocio.** El equipo de catálogo puede dar de alta artículos antes de clasificarlos. La categoría es opcional, pero un artículo pendiente de clasificación sigue siendo un artículo válido y debe poder encontrarse.
**Pasos para reproducir.**
1. Crea un producto activo con un SKU único sin guiones bajos ni porcentajes, por ejemplo `SQLD2-SIN-CATEGORIA`, nombre `Material pendiente SQLD2`, unidad `UNIT` y stock mínimo `0`. Deja `categoryId` sin informar y no asignes proveedor.                    
2. Guarda el ID devuelto y consulta el detalle de ese producto por su ID.
3. {
   "id": 152,
   "sku": "SQLD2-SIN-CATEGORIA-02",
   "barcode": "SQLD2-002",
   "name": "Material pendiente SQLD2",
   "description": "Producto de prueba sin categoría",
   "categoryId": null,
   "unit": "UNIT",
   "minimumStock": 0,
   "active": true,
   "version": 0,
   "createdAt": "2026-10-12T08:06:59.445696900Z",
   "updatedAt": "2026-10-12T08:06:59.445696900Z"
   }
3. En la consulta del catálogo, envía `search=SQLD2-SIN-CATEGORIA`, página `0`, tamaño `20` y orden `id`. No envíes otros filtros.
4. Como control, crea otro producto con un SKU diferente y una categoría válida; busca su SKU mediante `search`.
5. {
   "id": 153,
   "sku": "SQLD2-CON-CATEGORIA-02",
   "barcode": "SQLD2-003",
   "name": "Material clasificado SQLD2",
   "description": "Producto de prueba con categoría",
   "categoryId": 2,
   "unit": "UNIT",
   "minimumStock": 0,
   "active": true,
   "version": 0,
   "createdAt": "2026-10-12T08:08:28.744089600Z",
   "updatedAt": "2026-10-12T08:08:28.744089600Z"
   }
**Comportamiento esperado.** El primer producto aparece tanto en su detalle como en la búsqueda del catálogo, aunque todavía no tenga categoría.
**Comportamiento observado.** El alta responde HTTP `201` y el detalle responde HTTP `200` con `categoryId=null`. La búsqueda responde HTTP `200` con `content=[]` y `totalElements=0`. El producto clasificado del control sí aparece en su búsqueda.
**Terminología.**
- **Categoría:** clasificación empresarial opcional de los artículos.
- **Relación entre tablas:** conexión entre registros de recursos diferentes, como artículos y categorías.
- **JOIN:** operación de consulta que combina información relacionada.
- **`null`:** ausencia de un valor; no equivale a un identificador con valor cero.
## Ticket 3 — El resumen de un almacén no coincide con sus existencias por ubicación
**Contexto de negocio.** Un responsable coloca el mismo material en dos zonas del almacén.
dos localizacciones del almacen
{
"content": [
{
"id": 1,
"warehouseId": 1,
"code": "DEFAULT",
"description": "Default stock location",
"active": true
},
{
"id": 84,
"warehouseId": 1,
"code": "FV_01",
"description": "frutas y verduras",
"active": true
}
],
"totalElements": 2,
"page": 0,
"size": 20
}
Antes de preparar un pedido, consulta el resumen y encuentra menos unidades que las registradas en las zonas.
**Pasos para reproducir.**
1. Crea un almacén nuevo con tu cuenta. Identifica su ubicación `DEFAULT` y crea una segunda ubicación activa con código `B`.
2. Crea un producto activo nuevo, con una categoría válida, unidad `UNIT` y stock mínimo `0`. No utilices un producto con existencias previas.
3. {
   "id": 157,
   "sku": "Taladro penetrador soluciona problemas",
   "barcode": "BPSP-01",
   "name": "Taladro",
   "description": "Producto beta estrella",
   "categoryId": 2,
   "unit": "UNIT",
   "minimumStock": 0,
   "active": true,
   "version": 0,
   "createdAt": "2026-10-12T10:10:21.263790200Z",
   "updatedAt": "2026-10-12T10:10:21.263790200Z"
   }
3. Registra una entrada de **10 unidades** de ese producto en `DEFAULT`, indicando explícitamente el ID de la ubicación. Registra otra entrada de **10 unidades** en `B`.
   {
   "id": "ca7e3e23-9692-4cdd-8125-61cdf5849ccd",
   "movementNumber": "MOV-ca7e3e23-9692-4cdd-8125-61cdf5849ccd",
   "type": "RECEIPT",
   "status": "POSTED",
   "sourceWarehouseId": null,
   "targetWarehouseId": 81,
   "reason": "la razon vBug",
   "reference": "second matching point in south west warehouse",
   "createdBy": 2,
   "postedBy": 2,
   "createdAt": "2026-10-12T10:16:13.734587Z",
   "postedAt": "2026-10-12T10:16:13.734587Z",
   "compensatesMovementId": null,
   "transferGroupId": null,
   "purchaseOrderId": null,
   "lines": [
   {
   "id": 34,
   "productId": 1,
   "sourceLocationId": null,
   "targetLocationId": 85,
   "quantity": 10,
   "unitCost": null
   }
   ]
   },
{
"id": "ca7e3e23-9692-4cdd-8125-61cdf5849ccd",
"movementNumber": "MOV-ca7e3e23-9692-4cdd-8125-61cdf5849ccd",
"type": "RECEIPT",
"status": "POSTED",
"sourceWarehouseId": null,
"targetWarehouseId": 81,
"reason": "la razon vBug",
"reference": "second matching point in south west warehouse",
"createdBy": 2,
"postedBy": 2,
"createdAt": "2026-10-12T10:16:13.734587Z",
"postedAt": "2026-10-12T10:16:13.734587Z",
"compensatesMovementId": null,
"transferGroupId": null,
"purchaseOrderId": null,
"lines": [
{
"id": 34,
"productId": 1,
"sourceLocationId": null,
"targetLocationId": 85,
"quantity": 10,
"unitCost": null
}
]
}
4. Consulta las existencias por ubicación de ese almacén, filtrando por el producto creado.
5. {
   "content": [
   {
   "productId": 1,
   "warehouseId": 81,
   "locationId": 85,
   "quantity": 10,
   "reservedQuantity": 0,
   "availableQuantity": 10,
   "version": 1,
   "updatedAt": "2026-10-12T10:16:13.734587Z"
   },
   {
   "productId": 1,
   "warehouseId": 81,
   "locationId": 87,
   "quantity": 10,
   "reservedQuantity": 0,
   "availableQuantity": 10,
   "version": 1,
   "updatedAt": "2026-10-12T10:15:27.843036Z"
   }
   ],
   "totalElements": 2,
   "page": 0,
   "size": 20
   }
5. Consulta el resumen de existencias de ese mismo producto en ese almacén. Contrasta las cantidades, las reservas y la disponibilidad.
**Comportamiento esperado.** Las ubicaciones muestran `10` y `10`. El resumen muestra cantidad física `20`, reservada `0` y disponible `20`.
**Comportamiento observado.** Ambas entradas responden HTTP `201` y quedan contabilizadas. Las ubicaciones muestran correctamente `10` y `10`, pero el resumen responde HTTP `200` con cantidad física `10`, reservada `0` y disponible `10`.
**Terminología.**
- **Ubicación:** zona de almacenamiento dentro de un almacén.
- **Balance:** existencias registradas para un producto en una ubicación.
- **Cantidad física / reservada / disponible:** unidades presentes, unidades comprometidas y unidades que pueden utilizarse.
- **Agregación:** cálculo de un resultado a partir de varias filas.
- **GROUP BY:** agrupación de filas por una o varias claves para realizar cálculos por grupo.
## Ticket 4 — Avanzar de página repite el primer proveedor
**Contexto de negocio.** Compras recorre el directorio de proveedores con una página pequeña.
Al avanzar, vuelve a ver la primera ficha y no consigue acceder a la siguiente mediante la paginación.
**Pasos para reproducir.**
1. Asegura que existen al menos dos proveedores. 43 - 46
3. Si necesitas crearlos, utiliza códigos nuevos, nombres distintos y `active=true`.
2. Consulta el directorio con `page=0&size=2`. 
3. Anota los IDs del primer y segundo proveedor devueltos; 1 y 2
4. el directorio incluye también proveedores anteriores.
3. Consulta el mismo directorio con `page=0&size=1` y después con `page=1&size=1`.
4. {
   "content": [
   {
   "id": 1,
   "code": "SUP-LOCAL",
   "name": "Local supplies",
   "email": "sup-local@example.test",
   "phone": null,
   "active": true
   }
   ],
   "totalElements": 6,
   "page": 0,
   "size": 1
   }
5. {
   "content": [
   {
   "id": 1,
   "code": "SUP-LOCAL",
   "name": "Local supplies",
   "email": "sup-local@example.test",
   "phone": null,
   "active": true
   }
   ],
   "totalElements": 6,
   "page": 0,
   "size": 1
   }
4. Compara el ID de cada ficha, `page`, `size` y `totalElements`.
**Comportamiento esperado.** La primera petición de tamaño `1` muestra el primer proveedor. La segunda muestra el segundo proveedor y comunica `page=1`. El total de proveedores se mantiene.
**Comportamiento observado.** Las dos peticiones de tamaño `1` responden HTTP `200` con la misma primera ficha y `page=0`. El total indica que existen más proveedores.
**Terminología.**
- **Paginación:** división de un listado en grupos de tamaño limitado.
- **`page`:** índice de página que comienza en `0`.
- **`size`:** máximo de elementos solicitado por página.
- **DTO:** representación de datos que cruza la frontera de la API, incluidos los metadatos de una página.







## Ticket 5 — La modificación de una ficha conserva un teléfono anterior
**Contexto de negocio.** Un proveedor cambia de teléfono y de nombre comercial.
La edición se acepta y el nombre nuevo aparece, pero el equipo sigue encontrando el teléfono anterior al recuperar la ficha.
**Pasos para reproducir.**
1. Crea un proveedor con código único, nombre `Proveedor original`, email `orders@example.test`, teléfono `+34 910 111 111` y `active=true`.
   {
   "id": 94,
   "code": "SUP-international",
   "name": "Proveedor original",
   "email": "orders@example.test",
   "phone": "+34 910 111 111",
   "active": true
   }
2. Consulta su detalle y confirma los datos iniciales.
3. {
   "id": 94,
   "code": "SUP-international",
   "name": "Proveedor original",
   "email": "orders@example.test",
   "phone": "+34 910 111 111",
   "active": true
   }
3. Actualiza esa misma ficha con el cuerpo completo: conserva código, email y estado; cambia el nombre a `Proveedor actualizado` y el teléfono a `+34 910 222 222`.
4. Comprueba el estado HTTP y el contenido de la respuesta. Realiza una nueva petición de lectura del detalle.
5.
Response body
Download
{
"id": 91,
"code": "UNIQUE-bug5",
"name": "Proveedor actualizado",
"email": "orders@example.test",
"phone": "+34 910 111 111",
"active": true
}
{
"id": 91,
"code": "UNIQUE-bug5",
"name": "Proveedor actualizado",
"email": "orders@example.test",
"phone": "+34 910 111 111",
"active": true
}
5. Comprueba el registro persistido mediante SQL escrito por ti.
6. 91,UNIQUE-bug5,Proveedor actualizado,orders@example.test,+34 910 111 111,true,2026-10-12 11:54:55.180299 +00:00
6. Prepara desde cero un test de integración con datos propios, ejecuta la edición y contrasta el estado confirmado en PostgreSQL.
7. No dependas de los datos de desarrollo ni de una instancia conservada en memoria.

**Comportamiento esperado.** La edición responde HTTP `200`. La respuesta, una lectura posterior y el registro persistido contienen el nombre nuevo y el teléfono `+34 910 222 222`.
**Comportamiento observado.** La edición responde HTTP `200`. El nombre nuevo queda guardado, pero la respuesta, la lectura posterior y PostgreSQL conservan el teléfono `+34 910 111 111`.
**Terminología.**
- **Persistencia:** conservación de los cambios en la base de datos después de la operación.
- **Transacción:** unidad de trabajo que confirma o revierte sus escrituras conjuntamente.
- **Test de integración:** prueba del comportamiento de componentes conectados, incluida una base PostgreSQL real.
- **Preparación y aserción:** creación de las condiciones iniciales y comprobación explícita del resultado esperado de una prueba.

## Trabajo esperado para cada incidencia
Sigue el recorrido completo de la petición, reproduce con datos propios y escribe las consultas SQL que necesites para entender el resultado. Antes de modificar código, formula una hipótesis y registra evidencia. Justifica después la corrección con el comportamiento empresarial esperado y una comprobación de integración. Ejecuta la suite existente para detectar efectos sobre otros contratos.
## Registro de preparación
La creación de funcionalidades de Wave 2 queda cancelada. Se conservaron el trabajo local previo, la migración a JPA, las funcionalidades existentes, los datos y las migraciones V1–V7. No se identificó un bloque funcional nuevo atribuible con certeza a la Wave 2 actual; no se revirtió trabajo histórico.
La primera verificación detectó una discrepancia de nombre de tabla entre V7 y el mapping existente en una base vacía. V8 reconcilia ese nombre mediante un renombrado condicionado, sin crear funcionalidades ni borrar datos. La expectativa de versión del test de esquema se actualizó a `8`; se mantiene la comprobación de `24` tablas. Ningún test de negocio se cambió para introducir los ejercicios.
La base estable está conservada en la rama local `codex/warehouse-stable-sol-6.1`. Se volvió a verificar durante la limpieza SOL-6.1 con `clean verify`: 85 tests aprobados, sin fallos, errores ni omitidos; arranque real contra PostgreSQL, readiness `UP`, OpenAPI y Swagger HTTP `200`. Los cinco escenarios dieron el resultado esperado en esa base.
La rama de entrenamiento también volvió a pasar `.\mvnw.cmd clean verify`: **85 tests, 0 fallos, 0 errores y 0 omitidos**, contando los XML de Surefire. Los cinco comportamientos observados se reprodujeron mediante HTTP real contra PostgreSQL, con datos propios para cada escenario en la base aislada `warehouse_cleanup_verification_20261012`. Se contrastaron también las existencias físicas y el teléfono persistido. El arranque, readiness, OpenAPI y Swagger siguen respondiendo correctamente. Esta verificación no modificó la base `warehouse` ni la base de práctica anterior.
Los cinco cambios de entrenamiento afectan solo a tres archivos de producción respecto a la rama estable; los tests y las migraciones son idénticos entre ambas ramas. Cada escenario usa datos que evitan los otros defectos. Los cambios de entrenamiento y este documento se conservan en un commit separado de los dos commits de la base estable. No se añadieron endpoints, frameworks ni funcionalidades durante la limpieza y no se hizo push.
La copia recuperable anterior a la limpieza está en `C:\Users\balar\Desktop\warehouse-backend-backups\cleanup-20261012-065839`; incluye archivos sin seguimiento, el directorio Git original, un bundle completo, parches y las instrucciones `RECOVERY.md`. Las sondas, logs y evidencias de esta verificación quedan fuera del repositorio, junto a esa copia.
