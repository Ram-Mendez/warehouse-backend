ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_movement_type_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_movement_type_check
CHECK (movement_type IN ('RECEIPT','ISSUE','TRANSFER','ADJUSTMENT','RETURN','TRANSFER_OUT','TRANSFER_IN','COMPENSATION'));
ALTER TABLE stock_movement ADD COLUMN compensates_movement_id UUID REFERENCES stock_movement(id) UNIQUE;
ALTER TABLE stock_movement ADD COLUMN transfer_group_id UUID;
ALTER TABLE stock_movement ADD COLUMN purchase_order_id UUID REFERENCES purchase_order(id);
ALTER TABLE stock_movement ADD CONSTRAINT movement_compensation_shape CHECK
 ((movement_type = 'COMPENSATION') = (compensates_movement_id IS NOT NULL) AND compensates_movement_id IS DISTINCT FROM id);
ALTER TABLE stock_movement ADD CONSTRAINT movement_transfer_shape CHECK
 ((movement_type IN ('TRANSFER_OUT','TRANSFER_IN')) = (transfer_group_id IS NOT NULL)
 AND (movement_type NOT IN ('TRANSFER','TRANSFER_OUT','TRANSFER_IN') OR source_warehouse_id <> target_warehouse_id));
ALTER TABLE stock_movement ADD CONSTRAINT movement_posted_actor CHECK
 (status <> 'POSTED' OR (posted_at IS NOT NULL AND posted_by IS NOT NULL));
CREATE UNIQUE INDEX uq_transfer_leg ON stock_movement(transfer_group_id,movement_type) WHERE transfer_group_id IS NOT NULL;
CREATE UNIQUE INDEX uq_purchase_full_receipt ON stock_movement(purchase_order_id) WHERE purchase_order_id IS NOT NULL;
CREATE UNIQUE INDEX uq_refresh_child ON auth_refresh_token(parent_token_id) WHERE parent_token_id IS NOT NULL;

CREATE FUNCTION protect_posted_movement() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status = 'POSTED' THEN RAISE EXCEPTION 'Posted movement is immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_movement_immutable BEFORE UPDATE OR DELETE ON stock_movement
FOR EACH ROW EXECUTE FUNCTION protect_posted_movement();

CREATE FUNCTION protect_movement_line() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent_status TEXT; parent_id UUID;
BEGIN
 IF TG_OP='UPDATE' AND NEW.movement_id <> OLD.movement_id THEN
   RAISE EXCEPTION 'Movement line cannot change parent' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN parent_id:=OLD.movement_id; ELSE parent_id:=NEW.movement_id; END IF;
 SELECT status INTO parent_status FROM stock_movement WHERE id=parent_id FOR UPDATE;
 IF parent_status='POSTED' THEN RAISE EXCEPTION 'Posted movement lines are immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_line_immutable BEFORE INSERT OR UPDATE OR DELETE ON stock_movement_line
FOR EACH ROW EXECUTE FUNCTION protect_movement_line();

CREATE FUNCTION validate_movement_posting() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='POSTED' THEN
   IF NOT EXISTS (SELECT 1 FROM stock_movement_line WHERE movement_id=NEW.id) THEN
     RAISE EXCEPTION 'Posted movement requires lines' USING ERRCODE='23514';
   END IF;
   IF EXISTS (SELECT 1 FROM stock_movement_line l
       LEFT JOIN warehouse_location s ON s.id=l.source_location_id
       LEFT JOIN warehouse_location t ON t.id=l.target_location_id
       WHERE l.movement_id=NEW.id AND
       ((l.source_location_id IS NULL AND l.target_location_id IS NULL)
       OR (s.id IS NOT NULL AND s.warehouse_id IS DISTINCT FROM NEW.source_warehouse_id)
       OR (t.id IS NOT NULL AND t.warehouse_id IS DISTINCT FROM NEW.target_warehouse_id))) THEN
     RAISE EXCEPTION 'Movement location does not match warehouse' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_posting_valid BEFORE INSERT OR UPDATE ON stock_movement
FOR EACH ROW EXECUTE FUNCTION validate_movement_posting();

CREATE INDEX idx_movement_line_parent ON stock_movement_line(movement_id);
CREATE INDEX idx_movement_line_product ON stock_movement_line(product_id,movement_id);
CREATE INDEX idx_movement_source_time ON stock_movement(source_warehouse_id,created_at DESC);
CREATE INDEX idx_movement_target_time ON stock_movement(target_warehouse_id,created_at DESC);
CREATE INDEX idx_movement_audit_lookup ON stock_movement_audit(movement_id,changed_at DESC);
CREATE INDEX idx_purchase_warehouse_status ON purchase_order(target_warehouse_id,status,created_at DESC);
CREATE INDEX idx_purchase_line_parent ON purchase_order_line(purchase_order_id);
CREATE INDEX idx_product_category ON product(category_id);
CREATE INDEX idx_product_supplier_reverse ON product_supplier(supplier_id,product_id);
CREATE INDEX idx_scope_warehouse ON security_user_warehouse_scope(warehouse_id,user_id);

INSERT INTO security_permission(code,description) VALUES
 ('PERM_CATEGORY_READ','Read categories'),('PERM_CATEGORY_WRITE','Manage categories'),
 ('PERM_STOCK_RECEIVE','Receive stock'),('PERM_STOCK_ISSUE','Issue stock'),
 ('PERM_STOCK_TRANSFER','Transfer stock'),('PERM_PURCHASE_RECEIVE','Receive purchase orders');
INSERT INTO security_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM security_role r CROSS JOIN security_permission p
WHERE (r.code IN ('ROLE_ADMIN','ROLE_MANAGER') AND p.code IN
 ('PERM_CATEGORY_READ','PERM_CATEGORY_WRITE','PERM_STOCK_RECEIVE','PERM_STOCK_ISSUE','PERM_STOCK_TRANSFER','PERM_PURCHASE_RECEIVE'))
OR (r.code='ROLE_OPERATOR' AND p.code IN ('PERM_CATEGORY_READ','PERM_STOCK_RECEIVE','PERM_STOCK_ISSUE','PERM_STOCK_TRANSFER'))
OR (r.code IN ('ROLE_AUDITOR','ROLE_SUPPORT') AND p.code='PERM_CATEGORY_READ');
