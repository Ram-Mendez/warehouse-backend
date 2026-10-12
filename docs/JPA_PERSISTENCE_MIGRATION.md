# Migración de persistencia a JPA/Hibernate — 2026-10-12

La persistencia de aplicación queda centrada en Spring Data JPA/Hibernate. Los servicios
siguen siendo los límites de autorización y transacción; los contratos HTTP y DTO no cambian.

## Base de comparación y auditoría

La rama inicial era `training/warehouse-breakage-2` con trabajo local ya modificado,
incluidos faltantes, política de reposición, V7 y pruebas de operaciones. Se tomó una
copia previa del código y se comparó contra ella, no contra HEAD, para conservar ese trabajo.
No se ha creado un commit, cambiado de rama ni publicado cambios.

Antes de migrar había un `ProductRepository` JPA y las entidades `Product` y
`ProductReplenishmentPolicy`. La persistencia JDBC estaba en:

- `audit/AuditRepository`, `audit/AuditService`.
- `auth/AuthRepository`.
- `category/CategoryRepository`, `supplier/SupplierRepository`, `warehouse/WarehouseRepository`.
- `contact/ContactRepository`, `purchase/PurchaseRepository`.
- `inventory/InventoryRepository`, `inventory/InventoryQueryRepository`.
- `movement/MovementRepository`, `product/ProductQueryRepository`.
- `security/SecurityRepository`, `security/AdminRepository`.
- `config/DevDataInitializer`.

`common/Sql` contenía utilidades `ResultSet`; `ApiExceptionHandler` dependía de
excepciones de Spring JDBC. Los repositorios de listado construían SQL con filtros,
ordenación y paginación. Ahora se usan métodos derivados, JPQL, proyecciones y
`Pageable`; se conservan la lista permitida de ordenación y el escape literal de LIKE.
Las concatenaciones restantes unen fragmentos constantes de consultas parametrizadas.

Se revisaron V1–V7, las restricciones de stock, claves compuestas, triggers de auditoría,
inmutabilidad, referencias de transferencias/compras y el esquema de seguridad.
Todas las migraciones, `ddl-auto=validate`, configuración, dependencias, controladores
y DTO existentes permanecen idénticos a la copia inicial. No hace falta una migración nueva.

## Repositorios resultantes

| Módulo | JpaRepository | Entidades principales |
| --- | --- | --- |
| Auditoría | `AuditRepository` | `AuditEvent`, `StockMovementAudit` inmutable |
| Autenticación | `AuthRepository`, `RefreshTokenRepository` | `AuthSession`, `AuthRefreshToken` |
| Seguridad | `SecurityRepository`, `SecurityRoleRepository`, `WarehouseScopeRepository` | `SecurityUser`, `SecurityRole`, `SecurityPermission`, `WarehouseScope` |
| Categorías | `CategoryRepository` | `Category` y su padre opcional |
| Proveedores | `SupplierRepository` | `Supplier` |
| Productos | `ProductRepository` | `Product`, `ProductSupplier` con clave compuesta |
| Almacenes | `WarehouseRepository`, `WarehouseLocationRepository` | `Warehouse`, `WarehouseLocation` |
| Inventario | `InventoryRepository` | `InventoryBalance` |
| Movimientos | `MovementRepository` | `StockMovement`, `StockMovementLine` |
| Compras | `PurchaseRepository` | `PurchaseOrder`, `PurchaseOrderLine` |
| Contacto | `ContactRepository` | `ContactMessage` |

Hay 15 interfaces JpaRepository y 21 entidades contando la política de reposición
preexistente. No se han inventado endpoints de reservas ni reposición: `stock_reservation`
continúa como almacenamiento existente y los balances siguen respetando `reserved_quantity`.

`InventoryQueryRepository` y `ProductQueryRepository` se integran en sus repositorios JPA.
`AdminRepository` desaparece: `AdminService` usa los repositorios de usuarios, roles y scopes.
Los fragmentos estándar `MovementWrites`, `PurchaseWrites` y `AuditHistory` implementan
operaciones con EntityManager dentro del repositorio correspondiente; no son una capa JDBC alternativa.

## Garantías y decisiones

- `@Version` de producto se conserva sin modificar `Product`. Movimientos, compras y balances
  tienen mappings versionados. La actualización aritmética JPQL del balance incrementa explícitamente
  su versión, incluso con delta cero, y actualiza la fecha con el reloj de PostgreSQL.
- `@Lock(PESSIMISTIC_WRITE)` protege balances, compras, movimientos, usuarios y sesiones.
  El orden de bloqueo por ubicación/producto del servicio de movimientos permanece intacto.
- La actualización masiva de balances hace flush y clear para evitar cantidades/versiones antiguas
  en el contexto. Clear no libera bloqueos ni confirma la transacción. Las transiciones de compras
  usan métodos de entidad, válidos también cuando Hibernate devuelve un proxy.
- Un movimiento inserta y vacía el borrador y las líneas antes de actualizar a POSTED. Los triggers
  siguen observando INSERT/DRAFT y UPDATE/POSTED; no se sustituye la auditoría por listeners Java.
- `set_config('app.current_user_id', ..., true)` se ejecuta mediante JPA en la misma transacción
  antes de escribir movimientos. Balance, compra, movimiento y auditorías se confirman o revierten juntos.
- Los importes NUMERIC siguen siendo BigDecimal. Las líneas se refrescan tras la inserción para
  devolver la escala y el redondeo reales de PostgreSQL también en la primera respuesta.
- Refresh bloquea la sesión y vuelve a consultar una proyección del token. Así una lectura anterior
  no permite reutilizar datos obsoletos del contexto JPA. La revocación por reutilización se confirma antes del 401.
- La auditoría filtra los almacenes de ambas instantáneas OLD y NEW; permisos y scopes permanecen obligatorios.
- Los listados de movimientos cargan las líneas del conjunto de IDs de la página. Los proveedores
  de productos se cargan con join fetch; los DTO se construyen dentro de la transacción.
- El manejador HTTP reconoce SQLSTATE 55P03 a través de la excepción de Hibernate, conservando
  el conflicto 409 y su mensaje sin usar excepciones de Spring JDBC.

SQL nativo conservado exclusivamente a través de JPA/Hibernate:

| Operación | Motivo |
| --- | --- |
| Crear balance y vincular proveedor con ON CONFLICT | Atomicidad ante inserciones concurrentes |
| Inicializador de desarrollo | Upserts idempotentes y preservación de cuentas existentes |
| Bloqueo de jerarquía de categorías | Bloqueo asesor transaccional PostgreSQL |
| Actor de auditoría | Configuración local a la transacción usada por el trigger |
| Historial de movimientos | Extracción de almacenes desde JSONB OLD/NEW |
| Faltantes de inventario | Agregación por almacén, productos sin balance y orden por faltante |

## Verificación

La suite inicial de la copia de trabajo pasó 85 tests, sin fallos, errores ni omitidos.
La compilación y la suite final pasaron con `.\mvnw.cmd clean test` (BUILD SUCCESS,
salida 0, 1 min 47 s). Se contaron independientemente los XML de Surefire:
**85 tests, 0 fallos, 0 errores y 0 omitidos**, con PostgreSQL 18.6/Testcontainers.
El conjunto focalizado previo pasó 35/35 tests de inventario, movimientos, compras y administración.

| Clase | Tests aprobados |
| --- | ---: |
| `AuthIntegrationTest` | 12 |
| `CatalogSecurityIntegrationTest` | 14 |
| `config.JwtPropertiesTest` | 3 |
| `DatabaseIntegrityIntegrationTest` | 4 |
| `DevInitializationIntegrationTest` | 1 |
| `inventory.InventoryRepositoryTest` | 6 |
| `InventoryMovementIntegrationTest` | 15 |
| `OpenApiContractIntegrationTest` | 3 |
| `OperationsIntegrationTest` | 7 |
| `PurchaseContactAdminIntegrationTest` | 14 |
| `TrainingBatchTwoIntegrationTest` | 5 |
| `WarehouseBackendApplicationTests` | 1 |

Durante la migración las pruebas detectaron diferencias de escala decimal en primeras respuestas
y una escritura de estado sobre un proxy de compra después de limpiar el contexto. Ambas se
corrigieron en producción manteniendo las aserciones estrictas. La suite limpia confirma esas correcciones.

La búsqueda final en `src/main` devuelve cero referencias a JdbcTemplate, NamedParameterJdbcTemplate,
RowMapper, imports java.sql/javax.sql, DriverManager, prepareStatement, MyBatis o callbacks de acceso
a conexiones Hibernate. Los compilados de los tres repositorios eliminados tampoco existen tras clean.
`git diff --check` pasó. No quedó ningún test bloqueado por el entorno.


Se reforzaron tests existentes, sin duplicarlos ni reducir aserciones:

- Bloqueos reales observados mediante `pg_blocking_pids` y `pg_locks`, independientes del SQL generado.
- Versiones de balances, delta cero y conservación de reservas después de una salida válida o rechazada.
- Instantáneas DRAFT/POSTED y versiones 0/1 en el trigger; líneas asociadas correctamente en páginas.
- Compras con dos productos, cantidades/costes decimales diferentes, recepción única y relecturas consistentes.

Los tests mantienen JDBC para preparar datos, observar bloqueos y contrastar el estado físico
independientemente de Hibernate. No forma parte del código de aplicación ni de una implementación
paralela de persistencia. El driver PostgreSQL y el JDBC interno de Hibernate son necesarios.
`@JdbcTypeCode` y `org.hibernate.JDBCException` son APIs de Hibernate, no acceso JDBC directo.

## Archivos de esta migración

La lista siguiente compara con el estado local previo a la tarea; el diff global de Git
incluye además trabajo del usuario anterior a esta migración. El cambio Java de esta tarea comprende
24 archivos de producción modificados, 29 añadidos, 3 eliminados y 5 archivos de tests modificados:
1.710 líneas añadidas y 1.526 eliminadas respecto a la copia inicial. El `git diff --stat` global
muestra 47 archivos seguidos, +1.551/-1.756 líneas, pero mezcla trabajo previo y excluye archivos nuevos sin seguimiento.

### Clases modificadas

- [audit/AuditReadService.java](../src/main/java/com/rammendez/warehouse/audit/AuditReadService.java)
- [audit/AuditRepository.java](../src/main/java/com/rammendez/warehouse/audit/AuditRepository.java)
- [audit/AuditService.java](../src/main/java/com/rammendez/warehouse/audit/AuditService.java)
- [auth/AuthRepository.java](../src/main/java/com/rammendez/warehouse/auth/AuthRepository.java)
- [auth/AuthService.java](../src/main/java/com/rammendez/warehouse/auth/AuthService.java)
- [category/CategoryRepository.java](../src/main/java/com/rammendez/warehouse/category/CategoryRepository.java)
- [category/CategoryService.java](../src/main/java/com/rammendez/warehouse/category/CategoryService.java)
- [common/ApiExceptionHandler.java](../src/main/java/com/rammendez/warehouse/common/ApiExceptionHandler.java)
- [common/Sql.java](../src/main/java/com/rammendez/warehouse/common/Sql.java)
- [config/DevDataInitializer.java](../src/main/java/com/rammendez/warehouse/config/DevDataInitializer.java)
- [contact/ContactRepository.java](../src/main/java/com/rammendez/warehouse/contact/ContactRepository.java)
- [contact/ContactService.java](../src/main/java/com/rammendez/warehouse/contact/ContactService.java)
- [inventory/InventoryRepository.java](../src/main/java/com/rammendez/warehouse/inventory/InventoryRepository.java)
- [inventory/InventoryService.java](../src/main/java/com/rammendez/warehouse/inventory/InventoryService.java)
- [movement/MovementRepository.java](../src/main/java/com/rammendez/warehouse/movement/MovementRepository.java)
- [product/ProductRepository.java](../src/main/java/com/rammendez/warehouse/product/ProductRepository.java)
- [product/ProductService.java](../src/main/java/com/rammendez/warehouse/product/ProductService.java)
- [purchase/PurchaseRepository.java](../src/main/java/com/rammendez/warehouse/purchase/PurchaseRepository.java)
- [security/AdminService.java](../src/main/java/com/rammendez/warehouse/security/AdminService.java)
- [security/SecurityRepository.java](../src/main/java/com/rammendez/warehouse/security/SecurityRepository.java)
- [supplier/SupplierRepository.java](../src/main/java/com/rammendez/warehouse/supplier/SupplierRepository.java)
- [supplier/SupplierService.java](../src/main/java/com/rammendez/warehouse/supplier/SupplierService.java)
- [warehouse/WarehouseRepository.java](../src/main/java/com/rammendez/warehouse/warehouse/WarehouseRepository.java)
- [warehouse/WarehouseService.java](../src/main/java/com/rammendez/warehouse/warehouse/WarehouseService.java)
- [test/AuthIntegrationTest.java](../src/test/java/com/rammendez/warehouse/AuthIntegrationTest.java)
- [test/InventoryMovementIntegrationTest.java](../src/test/java/com/rammendez/warehouse/InventoryMovementIntegrationTest.java)
- [test/PostgresIntegrationTest.java](../src/test/java/com/rammendez/warehouse/PostgresIntegrationTest.java)
- [test/PurchaseContactAdminIntegrationTest.java](../src/test/java/com/rammendez/warehouse/PurchaseContactAdminIntegrationTest.java)
- [test/inventory/InventoryRepositoryTest.java](../src/test/java/com/rammendez/warehouse/inventory/InventoryRepositoryTest.java)

### Clases añadidas

- [audit/AuditEvent.java](../src/main/java/com/rammendez/warehouse/audit/AuditEvent.java)
- [audit/AuditHistory.java](../src/main/java/com/rammendez/warehouse/audit/AuditHistory.java)
- [audit/AuditHistoryImpl.java](../src/main/java/com/rammendez/warehouse/audit/AuditHistoryImpl.java)
- [audit/StockMovementAudit.java](../src/main/java/com/rammendez/warehouse/audit/StockMovementAudit.java)
- [auth/AuthRefreshToken.java](../src/main/java/com/rammendez/warehouse/auth/AuthRefreshToken.java)
- [auth/AuthSession.java](../src/main/java/com/rammendez/warehouse/auth/AuthSession.java)
- [auth/RefreshTokenRepository.java](../src/main/java/com/rammendez/warehouse/auth/RefreshTokenRepository.java)
- [category/Category.java](../src/main/java/com/rammendez/warehouse/category/Category.java)
- [contact/ContactMessage.java](../src/main/java/com/rammendez/warehouse/contact/ContactMessage.java)
- [inventory/InventoryBalance.java](../src/main/java/com/rammendez/warehouse/inventory/InventoryBalance.java)
- [movement/MovementWrites.java](../src/main/java/com/rammendez/warehouse/movement/MovementWrites.java)
- [movement/MovementWritesImpl.java](../src/main/java/com/rammendez/warehouse/movement/MovementWritesImpl.java)
- [movement/StockMovement.java](../src/main/java/com/rammendez/warehouse/movement/StockMovement.java)
- [movement/StockMovementLine.java](../src/main/java/com/rammendez/warehouse/movement/StockMovementLine.java)
- [product/ProductSupplier.java](../src/main/java/com/rammendez/warehouse/product/ProductSupplier.java)
- [purchase/PurchaseOrder.java](../src/main/java/com/rammendez/warehouse/purchase/PurchaseOrder.java)
- [purchase/PurchaseOrderLine.java](../src/main/java/com/rammendez/warehouse/purchase/PurchaseOrderLine.java)
- [purchase/PurchaseWrites.java](../src/main/java/com/rammendez/warehouse/purchase/PurchaseWrites.java)
- [purchase/PurchaseWritesImpl.java](../src/main/java/com/rammendez/warehouse/purchase/PurchaseWritesImpl.java)
- [security/SecurityPermission.java](../src/main/java/com/rammendez/warehouse/security/SecurityPermission.java)
- [security/SecurityRole.java](../src/main/java/com/rammendez/warehouse/security/SecurityRole.java)
- [security/SecurityRoleRepository.java](../src/main/java/com/rammendez/warehouse/security/SecurityRoleRepository.java)
- [security/SecurityUser.java](../src/main/java/com/rammendez/warehouse/security/SecurityUser.java)
- [security/WarehouseScope.java](../src/main/java/com/rammendez/warehouse/security/WarehouseScope.java)
- [security/WarehouseScopeRepository.java](../src/main/java/com/rammendez/warehouse/security/WarehouseScopeRepository.java)
- [supplier/Supplier.java](../src/main/java/com/rammendez/warehouse/supplier/Supplier.java)
- [warehouse/Warehouse.java](../src/main/java/com/rammendez/warehouse/warehouse/Warehouse.java)
- [warehouse/WarehouseLocation.java](../src/main/java/com/rammendez/warehouse/warehouse/WarehouseLocation.java)
- [warehouse/WarehouseLocationRepository.java](../src/main/java/com/rammendez/warehouse/warehouse/WarehouseLocationRepository.java)

### Clases eliminadas

- `inventory/InventoryQueryRepository.java`
- `product/ProductQueryRepository.java`
- `security/AdminRepository.java`

Documentación: README y el mapa de entrevistas actualizan sus referencias a persistencia;
este informe recoge el alcance y la entrega. Los informes históricos mantienen sus fechas y resultados originales.

## Límites

No se conocen defectos ni tareas pendientes de esta migración tras la validación indicada.

La migración no añade una API de reservas, un scheduler de expiración ni recepciones parciales.
Las reservas existentes y la disponibilidad del balance conservan su comportamiento.
La compatibilidad está respaldada por las pruebas de integración y la revisión del código;
no se ha realizado un benchmark de carga ni se han probado todas las intercalaciones posibles de concurrencia.
Las consultas nativas indicadas mantienen la dependencia de PostgreSQL existente en el proyecto.
