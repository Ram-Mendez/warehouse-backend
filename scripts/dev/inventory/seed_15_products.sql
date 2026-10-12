-- Ruta sugerida en el proyecto: scripts/dev/seed_15_products.sql
-- SOLO DESARROLLO: crea productos y saldos de ejemplo, sin movimientos ni auditoria.
-- Requiere al menos una fila existente en warehouse_location.
-- Repetible: no duplica productos ni saldos ya creados.
BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM warehouse_location) THEN
        RAISE EXCEPTION 'Antes de cargar el seed, crea una warehouse_location';
    END IF;
END $$;

INSERT INTO product (sku, name, minimum_stock)
VALUES
    ('DEMO-101', 'Teclado mecanico',             10),
    ('DEMO-102', 'Raton inalambrico',             10),
    ('DEMO-103', 'Monitor 24 pulgadas',            5),
    ('DEMO-104', 'Monitor 27 pulgadas',            4),
    ('DEMO-105', 'Hub USB-C',                     12),
    ('DEMO-106', 'Cable HDMI 2 m',                20),
    ('DEMO-107', 'Cable USB-C 1 m',               25),
    ('DEMO-108', 'Base para portatil',             8),
    ('DEMO-109', 'Auriculares con microfono',      6),
    ('DEMO-110', 'Webcam Full HD',                 7),
    ('DEMO-111', 'Adaptador Ethernet USB-C',      10),
    ('DEMO-112', 'Alfombrilla de raton',          15),
    ('DEMO-113', 'Disco SSD externo 1 TB',         5),
    ('DEMO-114', 'Memoria USB 64 GB',             30),
    ('DEMO-115', 'Cargador USB-C 65 W',           10)
ON CONFLICT (sku) DO NOTHING;

-- La primera ubicacion real, sin asumir que su ID sea 1.
-- Cantidades variadas: bajo minimo, igual al minimo, por encima y a cero.
INSERT INTO inventory_balance
    (product_id, warehouse_location_id, quantity, reserved_quantity)
SELECT p.id, l.id, s.quantity, s.reserved_quantity
FROM (VALUES
    ('DEMO-101', 12::numeric, 5::numeric),
    ('DEMO-102', 10, 0),
    ('DEMO-103',  3, 0),
    ('DEMO-104',  9, 2),
    ('DEMO-105',  0, 0),
    ('DEMO-106', 30, 0),
    ('DEMO-107', 18, 4),
    ('DEMO-108',  8, 0),
    ('DEMO-109',  2, 1),
    ('DEMO-110', 10, 3),
    ('DEMO-111', 15, 0),
    ('DEMO-112',  7, 0),
    ('DEMO-113',  5, 1),
    ('DEMO-114', 40, 10),
    ('DEMO-115',  1, 0)
) AS s(sku, quantity, reserved_quantity)
JOIN product p ON p.sku = s.sku
CROSS JOIN (SELECT id FROM warehouse_location ORDER BY id LIMIT 1) l
ON CONFLICT (product_id, warehouse_location_id) DO NOTHING;

COMMIT;

-- Comprobacion: deben aparecer 15 productos DEMO y 15 balances
-- si estos SKU no existian y al menos una ubicacion existia.
SELECT p.sku, p.name, p.minimum_stock,
       b.quantity AS physical_stock,
       b.reserved_quantity,
       b.quantity - b.reserved_quantity AS available_stock
FROM product p
LEFT JOIN inventory_balance b ON b.product_id = p.id
WHERE p.sku BETWEEN 'DEMO-101' AND 'DEMO-115'
ORDER BY p.sku;
