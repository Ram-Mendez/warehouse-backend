CREATE OR REPLACE FUNCTION app_actor_user_id()
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    value TEXT;
BEGIN
    value := current_setting('app.current_user_id', true);
    IF value IS NULL OR value = '' THEN
        RETURN NULL;
    END IF;
    RETURN value::BIGINT;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION audit_stock_movement_changes()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO stock_movement_audit(movement_id, operation, actor_user_id, db_user, new_row)
        VALUES (NEW.id, TG_OP, app_actor_user_id(), current_user, to_jsonb(NEW));
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        INSERT INTO stock_movement_audit(movement_id, operation, actor_user_id, db_user, old_row, new_row)
        VALUES (NEW.id, TG_OP, app_actor_user_id(), current_user, to_jsonb(OLD), to_jsonb(NEW));
        RETURN NEW;
    ELSE
        INSERT INTO stock_movement_audit(movement_id, operation, actor_user_id, db_user, old_row)
        VALUES (OLD.id, TG_OP, app_actor_user_id(), current_user, to_jsonb(OLD));
        RETURN OLD;
    END IF;
END;
$$;

CREATE TRIGGER trg_stock_movement_audit
AFTER INSERT OR UPDATE OR DELETE ON stock_movement
FOR EACH ROW EXECUTE FUNCTION audit_stock_movement_changes();
