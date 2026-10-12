**Criterios comunes de aceptación:
** contratos documentados en OpenAPI; validaciones y errores coherentes con la API; permisos y scope por almacén;
migraciones nuevas cuando correspondan, manteniendo
`ddl-auto=validate`; tests con PostgreSQL/Testcontainers y suite completa verde. Las operaciones que afecten al stock
deben demostrar atomicidad, auditoría y conservación del historial publicado.
---
**WARE-101 — Consultar productos con disponibilidad inferior al mínimo**
**Historia de usuario y objetivo de negocio**  
Como responsable de almacén, quiero consultar los productos cuya disponibilidad está por debajo del mínimo para
priorizar compras antes de que se agoten.
**Requisitos funcionales**

- Incorporar `GET /api/v1/warehouses/{warehouseId}/stock-shortages`.
- Comparar el mínimo global del producto con su disponibilidad total en el almacén, sumando sus ubicaciones y
  descontando reservas.
- Incluir productos activos sin saldo registrado, considerando disponibilidad cero.
- Devolver producto, stock físico, reservado, disponible, mínimo y cantidad faltante.
- Permitir filtro por categoría y paginación con orden estable.
- Requerir `PERM_INVENTORY_READ` y scope de lectura del almacén.

**Criterios de aceptación verificables**

- Con mínimo 10, stock físico 12 y reservado 5, el producto aparece con disponibilidad 7 y faltante 3.
- Con disponibilidad exactamente igual al mínimo, no aparece.
- Un producto activo sin saldos y mínimo 4 aparece con faltante 4.
- Los saldos de otro almacén no intervienen en el cálculo.
- Productos inactivos y productos de otra categoría quedan excluidos cuando corresponda.
- La paginación devuelve totales correctos y no duplica productos por tener varias ubicaciones.
- Un usuario sin scope recibe 403.

**Complejidad estimada:** **1/5**. Consulta agregada con reglas de negocio y casos límite.

**Pistas técnicas mínimas:** revisa `InventoryRepository`, `Product.minimumStock` y el contrato de `PageResponse`.

---

**WARE-102 — Configurar políticas de reposición por producto y almacén**

**Historia de usuario y objetivo de negocio**  
Como responsable de aprovisionamiento, quiero definir mínimos y objetivos diferentes por almacén para adaptar la
reposición a su demanda.
**Requisitos funcionales**
- Gestionar una política por combinación producto–almacén mediante un recurso REST propio.
- Cada política contiene mínimo, stock objetivo, indicador de activación y versión.
- Exigir mínimo no negativo y objetivo mayor o igual que el mínimo.
- -----------
- Consultar recomendaciones: si la disponibilidad está por debajo del mínimo, recomendar la cantidad necesaria para
  alcanzar el objetivo.
- Una política activa sustituye al mínimo global en ese almacén. Una política desactivada excluye esa combinación de las
  recomendaciones.
- Sin política específica, utilizar el mínimo global como mínimo y objetivo.
- Diferenciar el permiso de configuración del de consulta y registrar los cambios en auditoría.
- Las recomendaciones se consultan bajo demanda y no crean compras automáticamente.
**Criterios de aceptación verificables**
- Con disponibilidad 7, mínimo local 10 y objetivo 25, se recomiendan 18 unidades.
- El mismo producto puede tener políticas distintas en dos almacenes.
- Una política desactivada no genera recomendaciones.
- Sin política local, mínimo global 10 y disponibilidad 7 producen recomendación de 3.
- Objetivo inferior al mínimo devuelve 400 sin persistir cambios.
- No pueden existir dos políticas para la misma combinación.
- Dos actualizaciones con la misma versión inicial no sobrescriben silenciosamente sus cambios: una recibe 409.
- Configurar o consultar recomendaciones no modifica inventario ni crea movimientos.
**Complejidad estimada:** **2/5**. Nuevo recurso persistente, reglas de precedencia y control de edición concurrente.
**Pistas técnicas mínimas:** el mínimo global ya existe; la novedad es su configuración por almacén. Puedes implementar
este ticket independientemente de WARE-101 e integrar después ambas consultas.
---

**WARE-103 — Registrar entregas parciales de órdenes de compra**

**Historia de usuario y objetivo de negocio**  
Como operario de recepción, quiero registrar cada entrega parcial del proveedor para disponer de la mercancía recibida y
conocer lo que sigue pendiente.

**Requisitos funcionales**

- Añadir creación y consulta de recepciones bajo `/api/v1/purchase-orders/{id}/receipts`.
- Cada recepción identifica las líneas recibidas, sus cantidades y una referencia única dentro de la compra.
- Aceptar recepciones desde `SUBMITTED`, `APPROVED` y `PARTIALLY_RECEIVED`.
- Registrar únicamente cantidades positivas que no superen el pendiente de cada línea.
- Actualizar acumulados y generar los movimientos correspondientes a esa entrega.
- Mantener `PARTIALLY_RECEIVED` mientras quede mercancía pendiente y pasar a `RECEIVED` cuando todas las líneas estén
  completas.
- Conservar la recepción completa actual para compras sin recepciones previas.
- Repetir una referencia con el mismo contenido devuelve la recepción existente; reutilizarla con contenido diferente
  devuelve 409.
- Una compra parcialmente recibida no puede cancelarse mediante la cancelación general existente.

**Criterios de aceptación verificables**

- Para una línea de 10 unidades, recibir 4 deja recibido 4, pendiente 6 y aumenta stock en 4.
- Recibir después 6 completa la compra y deja un historial de ambas entregas.
- Una recepción puede incluir solo algunas líneas del pedido.
- Si una línea supera su pendiente, se rechaza toda la recepción sin cambios en stock, acumulados ni movimientos.
- Reintentar una recepción no duplica stock.
- Dos recepciones concurrentes no pueden superar conjuntamente lo pedido.
- Un fallo al publicar movimientos revierte también la recepción y los acumulados.
- Se mantienen los permisos y scopes de recepción existentes.

**Complejidad estimada:** **3/5**. Evolución del ciclo de compra, idempotencia y consistencia entre varios recursos.

**Pistas técnicas mínimas:** `PARTIALLY_RECEIVED` ya está declarado, pero no tiene un flujo operativo. Revisa tanto
`PurchaseService` como las restricciones actuales de recepción en PostgreSQL.

---

**WARE-104 — Reservar existencias para pedidos y consumirlas al preparar la salida**

**Historia de usuario y objetivo de negocio**  
Como responsable de preparación de pedidos, quiero reservar mercancía para una referencia comercial y consumir esa
reserva al despacharla, evitando comprometer las mismas unidades varias veces.

**Requisitos funcionales**

- Exponer creación, listado, detalle, cancelación y cumplimiento de reservas.
- Cada reserva corresponde a un producto, ubicación, cantidad, referencia comercial y vencimiento futuro.
- Gestionar los estados `ACTIVE`, `FULFILLED`, `CANCELLED` y `EXPIRED`.
- Crear una reserva reduce disponibilidad sin modificar stock físico.
- Cancelarla o caducarla libera las unidades.
- Cumplirla consume toda su cantidad y genera una salida vinculada a la reserva.
- Incorporar un proceso automático de caducidad; las reservas vencidas no pueden cumplirse aunque el proceso aún no las
  haya tratado.
- Aplicar permisos específicos, scope del almacén y auditoría de las transiciones.

**Criterios de aceptación verificables**

- Con stock 10, reservar 6 deja físico 10, reservado 6 y disponible 4.
- Intentar reservar otras 5 devuelve 409 sin cambios.
- Cancelar o caducar la reserva devuelve reservado a cero, manteniendo físico 10.
- Cumplirla deja físico 4, reservado cero y una única salida vinculada.
- Repetir el cumplimiento no genera otra salida.
- Dos reservas concurrentes no pueden comprometer más stock del disponible.
- Si cumplimiento, cancelación y caducidad compiten, solo una transición terminal produce efectos.
- Un fallo al publicar la salida conserva la reserva y los saldos anteriores.
- Los tests comprueban también la interacción con salidas y transferencias ordinarias.

**Complejidad estimada:** **4/5**. Ciclo de vida, ejecución automática y concurrencia entre distintas operaciones de
inventario.

**Pistas técnicas mínimas:** `stock_reservation` y `reserved_quantity` ya existen. La funcionalidad nueva es su gestión
operativa y su integración transaccional con los movimientos.

---

**WARE-105 — Realizar recuentos físicos y aprobar su conciliación**

**Historia de usuario y objetivo de negocio**  
Como responsable de inventario, quiero organizar recuentos físicos, revisar diferencias y aprobar su conciliación para
mantener existencias fiables con trazabilidad.

**Requisitos funcionales**

- Crear documentos de recuento para un almacén y una selección explícita de combinaciones producto–ubicación.
- Gestionar estados `DRAFT`, `SUBMITTED`, `POSTED` y `CANCELLED`.
- Conservar cantidades teóricas de referencia y registrar cantidades físicas contadas, incluyendo cero.
- Mostrar diferencias por línea antes de enviar a revisión.
- Exigir todas las líneas contadas para enviar el documento.
- Permitir devolver un documento enviado a borrador para repetir el recuento.
- Exigir un aprobador distinto del creador, con permiso de aprobación y scope adecuado.
- Al aprobar, publicar conjuntamente los ajustes necesarios y vincularlos al documento.
- Si cambió el inventario de alguna línea desde su referencia, rechazar la aprobación y exigir actualizar el recuento.
- Rechazar conciliaciones que dejen stock físico inferior al reservado.
- Los documentos publicados conservan su historial; las correcciones utilizan compensaciones.

**Criterios de aceptación verificables**

- Con cantidades teóricas 10 y 8, y recuentos 7 y 11, aprobar publica diferencias −3 y +3.
- Una línea sin diferencias no genera ajuste.
- Guardar o enviar el documento no altera stock.
- El creador no puede aprobar su propio documento.
- Si cambia una línea antes de aprobar, se devuelve 409 y ninguna línea se concilia.
- Un recuento inferior a las unidades reservadas rechaza toda la publicación.
- Dos aprobaciones concurrentes producen una única conciliación.
- Si falla uno de los ajustes, se revierten todos los ajustes, movimientos y cambios de estado.
- El detalle permite consultar creador, aprobador, fechas y movimientos generados.

**Complejidad estimada:** **5/5**. Documento de negocio con revisión, segregación de funciones y conciliación
concurrente de múltiples saldos.

**Pistas técnicas mínimas:** los ajustes firmados y su auditoría ya existen en `MovementService`. La ampliación consiste
en el documento, su ciclo de aprobación y la validez del recuento frente a operaciones concurrentes.
