-- V7 remains immutable. Some local databases already use the entity's table name.
-- Rename on fresh databases without recreating the table or touching its rows.
DO $$
BEGIN
    IF to_regclass('public.replenishment_policy') IS NOT NULL
       AND to_regclass('public.product_replenishment_policy') IS NULL THEN
        ALTER TABLE public.replenishment_policy RENAME TO product_replenishment_policy;
    END IF;
END
$$;
