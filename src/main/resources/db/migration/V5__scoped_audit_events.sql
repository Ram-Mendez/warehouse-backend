ALTER TABLE audit_event ADD COLUMN warehouse_id BIGINT REFERENCES warehouse(id);
ALTER TABLE audit_event ADD COLUMN related_warehouse_id BIGINT REFERENCES warehouse(id);
UPDATE audit_event e SET warehouse_id=m.source_warehouse_id,related_warehouse_id=m.target_warehouse_id
FROM stock_movement m WHERE e.entity_type='stock_movement' AND e.entity_id=m.id::text;
UPDATE audit_event e SET warehouse_id=p.target_warehouse_id
FROM purchase_order p WHERE e.entity_type='purchase_order' AND e.entity_id=p.id::text;
UPDATE audit_event e SET warehouse_id=w.id FROM warehouse w WHERE e.entity_type='warehouse' AND e.entity_id=w.id::text;
UPDATE audit_event e SET warehouse_id=l.warehouse_id FROM warehouse_location l WHERE e.entity_type='warehouse_location' AND e.entity_id=l.id::text;
UPDATE audit_event e SET warehouse_id=m.source_warehouse_id,related_warehouse_id=m.target_warehouse_id
FROM stock_movement m WHERE e.entity_type='transfer' AND e.entity_id=m.transfer_group_id::text;
CREATE INDEX idx_audit_event_warehouse ON audit_event(warehouse_id,occurred_at DESC);
