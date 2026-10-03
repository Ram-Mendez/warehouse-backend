INSERT INTO security_role(code, description) VALUES
('ROLE_ADMIN', 'Full platform administration'),
('ROLE_MANAGER', 'Warehouse and approval management'),
('ROLE_OPERATOR', 'Operational warehouse user'),
('ROLE_AUDITOR', 'Read-only audit user'),
('ROLE_SUPPORT', 'Contact/support user');

INSERT INTO security_permission(code, description) VALUES
('PERM_PRODUCT_READ', 'Read products'),
('PERM_PRODUCT_WRITE', 'Create/update products'),
('PERM_SUPPLIER_READ', 'Read suppliers'),
('PERM_SUPPLIER_WRITE', 'Create/update suppliers'),
('PERM_WAREHOUSE_READ', 'Read warehouses'),
('PERM_WAREHOUSE_MANAGE', 'Manage warehouses and locations'),
('PERM_INVENTORY_READ', 'Read inventory'),
('PERM_INVENTORY_ADJUST', 'Adjust inventory'),
('PERM_MOVEMENT_READ', 'Read movements'),
('PERM_MOVEMENT_CREATE', 'Create movements'),
('PERM_MOVEMENT_APPROVE', 'Approve movements'),
('PERM_MOVEMENT_POST', 'Post movements'),
('PERM_MOVEMENT_CANCEL', 'Cancel movements'),
('PERM_PURCHASE_READ', 'Read purchase orders'),
('PERM_PURCHASE_CREATE', 'Create purchase orders'),
('PERM_PURCHASE_APPROVE', 'Approve purchase orders'),
('PERM_CONTACT_READ', 'Read contact messages'),
('PERM_CONTACT_MANAGE', 'Manage contact messages'),
('PERM_AUDIT_READ', 'Read audit trail'),
('PERM_USER_MANAGE', 'Manage users/roles/scopes');

INSERT INTO security_role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
CROSS JOIN security_permission p
WHERE r.code = 'ROLE_ADMIN';

INSERT INTO security_role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p ON p.code IN (
    'PERM_PRODUCT_READ','PERM_PRODUCT_WRITE','PERM_SUPPLIER_READ','PERM_SUPPLIER_WRITE',
    'PERM_WAREHOUSE_READ','PERM_WAREHOUSE_MANAGE','PERM_INVENTORY_READ','PERM_INVENTORY_ADJUST',
    'PERM_MOVEMENT_READ','PERM_MOVEMENT_CREATE','PERM_MOVEMENT_APPROVE','PERM_MOVEMENT_POST','PERM_MOVEMENT_CANCEL',
    'PERM_PURCHASE_READ','PERM_PURCHASE_CREATE','PERM_PURCHASE_APPROVE','PERM_AUDIT_READ'
)
WHERE r.code = 'ROLE_MANAGER';

INSERT INTO security_role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p ON p.code IN (
    'PERM_PRODUCT_READ','PERM_SUPPLIER_READ','PERM_WAREHOUSE_READ','PERM_INVENTORY_READ',
    'PERM_MOVEMENT_READ','PERM_MOVEMENT_CREATE','PERM_PURCHASE_READ'
)
WHERE r.code = 'ROLE_OPERATOR';

INSERT INTO security_role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p ON p.code IN (
    'PERM_PRODUCT_READ','PERM_SUPPLIER_READ','PERM_WAREHOUSE_READ','PERM_INVENTORY_READ',
    'PERM_MOVEMENT_READ','PERM_PURCHASE_READ','PERM_AUDIT_READ'
)
WHERE r.code = 'ROLE_AUDITOR';

INSERT INTO security_role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p ON p.code IN ('PERM_CONTACT_READ','PERM_CONTACT_MANAGE','PERM_PRODUCT_READ')
WHERE r.code = 'ROLE_SUPPORT';
