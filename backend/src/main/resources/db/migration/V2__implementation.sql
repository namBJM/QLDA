SET search_path TO m01, public;
CREATE TABLE app_user(username token_text PRIMARY KEY,password_hash text NOT NULL,role text NOT NULL CHECK(role IN ('ACT02','ACT03','ACT09')),active boolean NOT NULL DEFAULT true);
CREATE TABLE user_warehouse(username text REFERENCES app_user,warehouse_id bigint REFERENCES warehouse,PRIMARY KEY(username,warehouse_id));
CREATE TABLE uom_conversion(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,sku_id bigint NOT NULL REFERENCES sku,uom_id bigint NOT NULL REFERENCES uom,factor numeric(18,6) NOT NULL CHECK(factor>0),active boolean NOT NULL DEFAULT true,UNIQUE(sku_id,uom_id));
ALTER TABLE receipt_line ADD COLUMN entered_qty qty, ADD COLUMN conversion_factor numeric(18,6) NOT NULL DEFAULT 1;
ALTER TABLE shipment_line ADD COLUMN entered_qty qty, ADD COLUMN conversion_factor numeric(18,6) NOT NULL DEFAULT 1;
ALTER TABLE count_item ADD COLUMN book_at_t0 qty NOT NULL DEFAULT 0,ADD COLUMN t0_seq bigint NOT NULL DEFAULT 0;
CREATE TABLE evidence(id uuid PRIMARY KEY,warehouse_id bigint NOT NULL REFERENCES warehouse,uploaded_by text NOT NULL REFERENCES app_user,original_name text NOT NULL,content_type text NOT NULL,size_bytes bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE INDEX conversion_sku ON uom_conversion(sku_id);
