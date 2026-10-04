ALTER TABLE stock_movement DROP CONSTRAINT movement_transfer_shape;
ALTER TABLE stock_movement ADD CONSTRAINT movement_transfer_shape CHECK
 ((movement_type IN ('TRANSFER_OUT','TRANSFER_IN')) = (transfer_group_id IS NOT NULL)
 AND (movement_type NOT IN ('TRANSFER','TRANSFER_OUT','TRANSFER_IN') OR
 (source_warehouse_id IS NOT NULL AND target_warehouse_id IS NOT NULL AND source_warehouse_id <> target_warehouse_id)));
ALTER TABLE auth_refresh_token ADD CONSTRAINT refresh_identity_session UNIQUE(id,session_id);
ALTER TABLE auth_refresh_token ADD CONSTRAINT refresh_parent_family FOREIGN KEY(parent_token_id,session_id) REFERENCES auth_refresh_token(id,session_id);
ALTER TABLE auth_refresh_token ADD CONSTRAINT refresh_not_self_parent CHECK(parent_token_id IS DISTINCT FROM id);
ALTER TABLE auth_refresh_token ADD CONSTRAINT refresh_hash_format CHECK(token_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE purchase_order_line ADD CONSTRAINT purchase_one_product UNIQUE(purchase_order_id,product_id);

CREATE OR REPLACE FUNCTION validate_movement_posting() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original stock_movement%ROWTYPE; purchase purchase_order%ROWTYPE;
BEGIN
 IF NEW.status<>'POSTED' THEN RETURN NEW; END IF;
 IF NOT EXISTS (SELECT 1 FROM stock_movement_line WHERE movement_id=NEW.id) THEN
   RAISE EXCEPTION 'Posted movement requires lines' USING ERRCODE='23514';
 END IF;
 IF EXISTS (SELECT 1 FROM stock_movement_line l
     LEFT JOIN warehouse_location s ON s.id=l.source_location_id
     LEFT JOIN warehouse_location t ON t.id=l.target_location_id
     WHERE l.movement_id=NEW.id AND
     ((l.source_location_id IS NULL AND l.target_location_id IS NULL)
     OR (s.id IS NOT NULL AND s.warehouse_id IS DISTINCT FROM NEW.source_warehouse_id)
     OR (t.id IS NOT NULL AND t.warehouse_id IS DISTINCT FROM NEW.target_warehouse_id)
     OR (NEW.movement_type IN ('RECEIPT','RETURN','TRANSFER_IN') AND (l.source_location_id IS NOT NULL OR l.target_location_id IS NULL))
     OR (NEW.movement_type IN ('ISSUE','TRANSFER_OUT') AND (l.target_location_id IS NOT NULL OR l.source_location_id IS NULL)))) THEN
   RAISE EXCEPTION 'Movement line direction or warehouse is invalid' USING ERRCODE='23514';
 END IF;
 IF NEW.compensates_movement_id IS NOT NULL THEN
   SELECT * INTO original FROM stock_movement WHERE id=NEW.compensates_movement_id FOR UPDATE;
   IF original.status<>'POSTED' OR original.movement_type='COMPENSATION'
       OR NEW.source_warehouse_id IS DISTINCT FROM original.target_warehouse_id
       OR NEW.target_warehouse_id IS DISTINCT FROM original.source_warehouse_id THEN
     RAISE EXCEPTION 'Compensation requires a reversed original posted movement' USING ERRCODE='23514';
   END IF;
   IF EXISTS (
     (SELECT product_id,source_location_id,target_location_id,quantity,unit_cost FROM stock_movement_line WHERE movement_id=NEW.id
      EXCEPT ALL SELECT product_id,target_location_id,source_location_id,quantity,unit_cost FROM stock_movement_line WHERE movement_id=original.id)
     UNION ALL
     (SELECT product_id,target_location_id,source_location_id,quantity,unit_cost FROM stock_movement_line WHERE movement_id=original.id
      EXCEPT ALL SELECT product_id,source_location_id,target_location_id,quantity,unit_cost FROM stock_movement_line WHERE movement_id=NEW.id)) THEN
     RAISE EXCEPTION 'Compensation lines must exactly reverse original lines' USING ERRCODE='23514';
   END IF;
 END IF;
 IF NEW.purchase_order_id IS NOT NULL THEN
   SELECT * INTO purchase FROM purchase_order WHERE id=NEW.purchase_order_id FOR UPDATE;
   IF NEW.movement_type<>'RECEIPT' OR NEW.source_warehouse_id IS NOT NULL
       OR NEW.target_warehouse_id IS DISTINCT FROM purchase.target_warehouse_id
       OR purchase.status NOT IN ('SUBMITTED','APPROVED') THEN
     RAISE EXCEPTION 'Invalid purchase receipt' USING ERRCODE='23514';
   END IF;
   IF EXISTS (
     (SELECT product_id,quantity,unit_cost FROM stock_movement_line WHERE movement_id=NEW.id
      EXCEPT ALL SELECT product_id,ordered_quantity,unit_cost FROM purchase_order_line WHERE purchase_order_id=purchase.id)
     UNION ALL
     (SELECT product_id,ordered_quantity,unit_cost FROM purchase_order_line WHERE purchase_order_id=purchase.id
      EXCEPT ALL SELECT product_id,quantity,unit_cost FROM stock_movement_line WHERE movement_id=NEW.id)) THEN
     RAISE EXCEPTION 'Purchase receipt must match full order lines' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END; $$;

CREATE FUNCTION validate_transfer_pair() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE group_id UUID; outbound stock_movement%ROWTYPE; inbound stock_movement%ROWTYPE;
BEGIN
 IF TG_OP='DELETE' THEN group_id:=OLD.transfer_group_id; ELSE group_id:=NEW.transfer_group_id; END IF;
 IF group_id IS NULL THEN RETURN NULL; END IF;
 IF (SELECT count(*) FROM stock_movement WHERE transfer_group_id=group_id)=0 THEN RETURN NULL; END IF;
 SELECT * INTO outbound FROM stock_movement WHERE transfer_group_id=group_id AND movement_type='TRANSFER_OUT';
 SELECT * INTO inbound FROM stock_movement WHERE transfer_group_id=group_id AND movement_type='TRANSFER_IN';
 IF outbound.id IS NULL OR inbound.id IS NULL OR outbound.status<>'POSTED' OR inbound.status<>'POSTED'
     OR outbound.source_warehouse_id IS DISTINCT FROM inbound.source_warehouse_id
     OR outbound.target_warehouse_id IS DISTINCT FROM inbound.target_warehouse_id THEN
   RAISE EXCEPTION 'Transfer requires matching posted OUT and IN legs' USING ERRCODE='23514';
 END IF;
 IF EXISTS (
   (SELECT product_id,quantity,unit_cost FROM stock_movement_line WHERE movement_id=outbound.id
    EXCEPT ALL SELECT product_id,quantity,unit_cost FROM stock_movement_line WHERE movement_id=inbound.id)
   UNION ALL
   (SELECT product_id,quantity,unit_cost FROM stock_movement_line WHERE movement_id=inbound.id
    EXCEPT ALL SELECT product_id,quantity,unit_cost FROM stock_movement_line WHERE movement_id=outbound.id)) THEN
   RAISE EXCEPTION 'Transfer lines must match' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER trg_transfer_pair AFTER INSERT OR UPDATE OR DELETE ON stock_movement
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_transfer_pair();
