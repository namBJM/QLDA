CREATE SCHEMA IF NOT EXISTS m01;
SET search_path TO m01, public;

-- Mã chủ thể lấy từ nền tảng; lượng theo đơn vị cơ sở.
CREATE DOMAIN qty AS numeric(18,3) CHECK (VALUE >= 0);
CREATE DOMAIN token_text AS text CHECK (length(btrim(VALUE)) > 0);

CREATE TABLE warehouse (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code token_text NOT NULL UNIQUE,
  name token_text NOT NULL,
  description text,
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0
);
CREATE TABLE location (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL REFERENCES warehouse(id),
  parent_id bigint,
  kind text NOT NULL CHECK (kind IN ('AREA','AISLE','BIN')),
  code token_text NOT NULL,
  name token_text NOT NULL,
  capacity_note text,
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (warehouse_id,id),
  UNIQUE (warehouse_id,code),
  FOREIGN KEY (warehouse_id,parent_id) REFERENCES location(warehouse_id,id),
  CHECK ((parent_id IS NULL) = (kind = 'AREA')),
  CHECK (parent_id IS DISTINCT FROM id)
);
CREATE TABLE uom (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code token_text NOT NULL UNIQUE,
  name token_text NOT NULL,
  symbol text,
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0
);
CREATE TABLE sku (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  uom_id bigint NOT NULL REFERENCES uom(id),
  code token_text NOT NULL UNIQUE,
  barcode token_text UNIQUE,
  name token_text NOT NULL,
  category text,
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0
);
CREATE TABLE import_preview (
  id uuid PRIMARY KEY,
  warehouse_id bigint NOT NULL REFERENCES warehouse(id),
  uploaded_by token_text NOT NULL,
  file_hash token_text NOT NULL,
  rows_json jsonb NOT NULL CHECK (jsonb_typeof(rows_json) = 'array'),
  expires_at timestamptz NOT NULL,
  applied_at timestamptz,
  result_json jsonb
);

-- Hàng khóa tổng hợp theo kho/SKU, kể cả khi chưa có số dư.
CREATE TABLE warehouse_sku (
  warehouse_id bigint NOT NULL REFERENCES warehouse(id),
  sku_id bigint NOT NULL REFERENCES sku(id),
  min_qty qty,
  threshold_set_at timestamptz,
  threshold_set_by token_text,
  version bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (warehouse_id,sku_id)
);
CREATE TABLE stock_balance (
  warehouse_id bigint NOT NULL,
  location_id bigint NOT NULL,
  sku_id bigint NOT NULL,
  book_qty qty NOT NULL DEFAULT 0,
  held_qty qty NOT NULL DEFAULT 0,
  blocked_qty qty NOT NULL DEFAULT 0,
  available_qty numeric(18,3) GENERATED ALWAYS AS
    (book_qty - held_qty - blocked_qty) STORED,
  last_seq bigint NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
  last_posted_at timestamptz,
  PRIMARY KEY (warehouse_id,location_id,sku_id),
  FOREIGN KEY (warehouse_id,sku_id) REFERENCES warehouse_sku,
  FOREIGN KEY (warehouse_id,location_id) REFERENCES location(warehouse_id,id),
  CHECK (book_qty >= held_qty + blocked_qty)
);
CREATE TABLE low_stock_alert (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL,
  sku_id bigint NOT NULL,
  status text NOT NULL CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED')),
  observed_qty qty NOT NULL,
  threshold_qty qty NOT NULL,
  opened_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  handled_by token_text,
  resolution text,
  version bigint NOT NULL DEFAULT 0,
  FOREIGN KEY (warehouse_id,sku_id) REFERENCES warehouse_sku
);
CREATE UNIQUE INDEX one_active_alert
  ON low_stock_alert(warehouse_id,sku_id) WHERE status <> 'RESOLVED';

CREATE TABLE receipt (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL REFERENCES warehouse(id),
  code token_text NOT NULL UNIQUE,
  source_type token_text,
  source_ref token_text,
  source_occurrence_ref token_text,
  source_date date,
  sender_name text,
  status text NOT NULL CHECK (status IN
    ('DRAFT','RECEIVING','DISCREPANCY','PENDING','RETURNED',
     'READY','POSTED','CANCELLED')),
  created_by token_text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  actual_at timestamptz,
  late_reason text,
  discrepancy_draft jsonb,
  correction_rev bigint NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (warehouse_id,id),
  UNIQUE (warehouse_id,source_type,source_ref,source_occurrence_ref),
  CHECK (status IN ('DRAFT','CANCELLED') OR
    (source_type IS NOT NULL AND source_ref IS NOT NULL)),
  CHECK (actual_at IS NULL OR nullif(btrim(late_reason),'') IS NOT NULL)
);
CREATE TABLE receipt_line (
  receipt_id bigint NOT NULL,
  line_no integer NOT NULL CHECK (line_no > 0),
  warehouse_id bigint NOT NULL,
  sku_id bigint NOT NULL REFERENCES sku(id),
  location_id bigint,
  uom_id bigint NOT NULL REFERENCES uom(id),
  expected_qty qty NOT NULL,
  received_qty qty,
  accepted_qty qty,
  blocked_qty qty NOT NULL DEFAULT 0,
  condition_note text,
  PRIMARY KEY (receipt_id,line_no),
  FOREIGN KEY (warehouse_id,receipt_id) REFERENCES receipt(warehouse_id,id),
  FOREIGN KEY (warehouse_id,location_id) REFERENCES location(warehouse_id,id),
  CHECK (accepted_qty IS NULL OR accepted_qty <= coalesce(received_qty,0)),
  CHECK (blocked_qty <= coalesce(accepted_qty,received_qty,0))
);
CREATE TABLE shipment (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL REFERENCES warehouse(id),
  code token_text NOT NULL UNIQUE,
  source_type token_text,
  source_ref token_text,
  status text NOT NULL CHECK (status IN
    ('DRAFT','PENDING','RETURNED','READY','PICKING','ISSUE',
     'PICKED','POSTED','CANCELLED')),
  created_by token_text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  issue_reason text,
  issue_evidence jsonb,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (warehouse_id,id),
  CHECK (status IN ('DRAFT','CANCELLED') OR
    (source_type IS NOT NULL AND source_ref IS NOT NULL))
);
CREATE TABLE shipment_line (
  shipment_id bigint NOT NULL,
  line_no integer NOT NULL CHECK (line_no > 0),
  warehouse_id bigint NOT NULL,
  sku_id bigint NOT NULL REFERENCES sku(id),
  uom_id bigint NOT NULL REFERENCES uom(id),
  requested_qty qty NOT NULL CHECK (requested_qty > 0),
  PRIMARY KEY (shipment_id,line_no),
  UNIQUE (warehouse_id,shipment_id,line_no,sku_id),
  FOREIGN KEY (warehouse_id,shipment_id) REFERENCES shipment(warehouse_id,id)
);
CREATE TABLE reservation (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL,
  shipment_id bigint NOT NULL,
  line_no integer NOT NULL,
  location_id bigint NOT NULL,
  sku_id bigint NOT NULL,
  qty_held qty NOT NULL CHECK (qty_held > 0),
  qty_picked qty NOT NULL DEFAULT 0,
  status text NOT NULL CHECK (status IN ('ACTIVE','RELEASED','CONSUMED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  ended_at timestamptz,
  FOREIGN KEY (warehouse_id,shipment_id,line_no,sku_id)
    REFERENCES shipment_line(warehouse_id,shipment_id,line_no,sku_id),
  FOREIGN KEY (warehouse_id,location_id,sku_id) REFERENCES stock_balance,
  CHECK (qty_picked <= qty_held),
  CHECK ((ended_at IS NULL) = (status = 'ACTIVE'))
);
CREATE UNIQUE INDEX one_active_reservation
  ON reservation(shipment_id,line_no,location_id) WHERE status = 'ACTIVE';
CREATE TABLE handover (
  shipment_id bigint PRIMARY KEY REFERENCES shipment(id),
  handed_at timestamptz NOT NULL,
  handed_by token_text NOT NULL,
  receiver_name token_text NOT NULL,
  source_ref text,
  snapshot jsonb NOT NULL
);

CREATE TABLE count_session (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL REFERENCES warehouse(id),
  code token_text NOT NULL UNIQUE,
  status text NOT NULL CHECK (status IN ('DRAFT','OPEN','CLOSED')),
  planned_at timestamptz NOT NULL,
  owner_id token_text NOT NULL,
  opened_at timestamptz,
  closed_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (warehouse_id,id),
  CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
CREATE TABLE count_item (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL,
  session_id bigint NOT NULL,
  location_id bigint NOT NULL,
  sku_id bigint NOT NULL,
  assigned_to token_text,
  status text NOT NULL CHECK (status IN
    ('WAITING','ASSIGNED','COUNTING','COUNTED','VARIANCE',
     'PENDING','RECOUNT','MATCHED','ADJUSTED','CLOSED')),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (session_id,location_id,sku_id),
  UNIQUE (warehouse_id,id),
  UNIQUE (warehouse_id,id,location_id,sku_id),
  FOREIGN KEY (warehouse_id,session_id) REFERENCES count_session(warehouse_id,id),
  FOREIGN KEY (warehouse_id,location_id,sku_id) REFERENCES stock_balance
);
CREATE UNIQUE INDEX one_open_count_item
  ON count_item(warehouse_id,location_id,sku_id) WHERE status <> 'CLOSED';
CREATE TABLE count_attempt (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL,
  item_id bigint NOT NULL,
  location_id bigint NOT NULL,
  sku_id bigint NOT NULL,
  attempt_no integer NOT NULL CHECK (attempt_no > 0),
  counted_by token_text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz,
  counted_at timestamptz,
  counted_qty qty,
  condition_note text,
  book_at_count qty,
  cutoff_seq bigint CHECK (cutoff_seq >= 0),
  reconcile_seq bigint CHECK (reconcile_seq >= cutoff_seq),
  reconciled_at timestamptz,
  reconciled_by token_text,
  superseded_at timestamptz,
  supersede_reason text,
  delta numeric(18,3) GENERATED ALWAYS AS
    (counted_qty - book_at_count) STORED,
  UNIQUE (item_id,attempt_no),
  UNIQUE (warehouse_id,id),
  FOREIGN KEY (warehouse_id,item_id,location_id,sku_id)
    REFERENCES count_item(warehouse_id,id,location_id,sku_id),
  CHECK (num_nonnulls(counted_at,counted_qty,book_at_count,cutoff_seq) IN (0,4)),
  CHECK (num_nonnulls(reconcile_seq,reconciled_at,reconciled_by) IN (0,3)),
  CHECK (reconciled_at IS NULL OR counted_at IS NOT NULL)
);
-- Lượt hiện hành đang đếm là khóa nghiệp vụ bền qua lần gọi API.
CREATE UNIQUE INDEX one_current_attempt
  ON count_attempt(item_id) WHERE superseded_at IS NULL;
CREATE TABLE count_minutes (
  session_id bigint PRIMARY KEY REFERENCES count_session(id),
  locked_at timestamptz NOT NULL,
  locked_by token_text NOT NULL,
  note text,
  snapshot jsonb NOT NULL
);
CREATE TABLE adjustment (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL REFERENCES warehouse(id),
  kind text NOT NULL CHECK (kind IN ('COUNT','IN_REVERSE','IN_CORRECT')),
  receipt_id bigint,
  receipt_rev bigint,
  count_attempt_id bigint,
  status text NOT NULL CHECK (status IN
    ('PENDING','POSTED','REJECTED','RECOUNT','SUPERSEDED')),
  created_by token_text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  reason token_text NOT NULL,
  evidence jsonb,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (warehouse_id,id),
  FOREIGN KEY (warehouse_id,receipt_id) REFERENCES receipt(warehouse_id,id),
  FOREIGN KEY (warehouse_id,count_attempt_id)
    REFERENCES count_attempt(warehouse_id,id),
  CHECK (
    (kind = 'COUNT' AND count_attempt_id IS NOT NULL
      AND receipt_id IS NULL AND receipt_rev IS NULL)
    OR
    (kind <> 'COUNT' AND count_attempt_id IS NULL
      AND receipt_id IS NOT NULL AND receipt_rev IS NOT NULL)
  )
);
CREATE UNIQUE INDEX one_live_count_adjustment
  ON adjustment(count_attempt_id)
  WHERE count_attempt_id IS NOT NULL AND status IN ('PENDING','POSTED');
CREATE UNIQUE INDEX one_pending_receipt_adjustment
  ON adjustment(receipt_id) WHERE receipt_id IS NOT NULL AND status = 'PENDING';
CREATE UNIQUE INDEX one_receipt_reversal
  ON adjustment(receipt_id) WHERE kind = 'IN_REVERSE' AND status = 'POSTED';
CREATE TABLE adjustment_line (
  adjustment_id bigint NOT NULL,
  line_no integer NOT NULL CHECK (line_no > 0),
  warehouse_id bigint NOT NULL,
  location_id bigint NOT NULL,
  sku_id bigint NOT NULL,
  original_line_no integer,
  delta_book numeric(18,3) NOT NULL,
  delta_blocked numeric(18,3) NOT NULL DEFAULT 0,
  PRIMARY KEY (adjustment_id,line_no),
  FOREIGN KEY (warehouse_id,adjustment_id)
    REFERENCES adjustment(warehouse_id,id),
  FOREIGN KEY (warehouse_id,location_id,sku_id) REFERENCES stock_balance,
  CHECK (delta_book <> 0 OR delta_blocked <> 0)
);
CREATE TABLE approval (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL REFERENCES warehouse(id),
  receipt_id bigint,
  shipment_id bigint,
  adjustment_id bigint,
  policy_ref token_text NOT NULL,
  level_code text NOT NULL CHECK (level_code IN ('ACT02','ACT09')),
  target_version bigint NOT NULL,
  submitted_by token_text NOT NULL,
  submitted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  payload jsonb NOT NULL,
  status text NOT NULL CHECK (status IN
    ('PENDING','APPROVED','REJECTED','SUPERSEDED')),
  decided_by token_text,
  decided_at timestamptz,
  reason text,
  FOREIGN KEY (warehouse_id,receipt_id) REFERENCES receipt(warehouse_id,id),
  FOREIGN KEY (warehouse_id,shipment_id) REFERENCES shipment(warehouse_id,id),
  FOREIGN KEY (warehouse_id,adjustment_id)
    REFERENCES adjustment(warehouse_id,id),
  CHECK (num_nonnulls(receipt_id,shipment_id,adjustment_id) = 1),
  CHECK (decided_by IS DISTINCT FROM submitted_by),
  CHECK ((status IN ('APPROVED','REJECTED')) = (decided_by IS NOT NULL)),
  CHECK ((decided_by IS NULL) = (decided_at IS NULL)),
  CHECK (status <> 'REJECTED' OR nullif(btrim(reason),'') IS NOT NULL)
);
CREATE UNIQUE INDEX one_pending_receipt_approval
  ON approval(receipt_id) WHERE receipt_id IS NOT NULL AND status = 'PENDING';
CREATE UNIQUE INDEX one_pending_shipment_approval
  ON approval(shipment_id) WHERE shipment_id IS NOT NULL AND status = 'PENDING';
CREATE UNIQUE INDEX one_pending_adjustment_approval
  ON approval(adjustment_id)
  WHERE adjustment_id IS NOT NULL AND status = 'PENDING';

-- Chỉ tạo nhóm biến động trong giao dịch xác nhận thành công.
CREATE TABLE movement_batch (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint NOT NULL REFERENCES warehouse(id),
  receipt_id bigint UNIQUE,
  shipment_id bigint UNIQUE,
  adjustment_id bigint UNIQUE,
  posted_at timestamptz NOT NULL,
  posted_by token_text NOT NULL,
  UNIQUE (warehouse_id,id),
  FOREIGN KEY (warehouse_id,receipt_id) REFERENCES receipt(warehouse_id,id),
  FOREIGN KEY (warehouse_id,shipment_id) REFERENCES shipment(warehouse_id,id),
  FOREIGN KEY (warehouse_id,adjustment_id)
    REFERENCES adjustment(warehouse_id,id),
  CHECK (num_nonnulls(receipt_id,shipment_id,adjustment_id) = 1)
);
CREATE TABLE movement_line (
  batch_id bigint NOT NULL,
  warehouse_id bigint NOT NULL,
  location_id bigint NOT NULL,
  sku_id bigint NOT NULL,
  seq bigint NOT NULL CHECK (seq > 0),
  before_book qty NOT NULL,
  delta_book numeric(18,3) NOT NULL,
  after_book qty NOT NULL,
  before_blocked qty NOT NULL,
  delta_blocked numeric(18,3) NOT NULL,
  after_blocked qty NOT NULL,
  PRIMARY KEY (batch_id,location_id,sku_id),
  UNIQUE (warehouse_id,location_id,sku_id,seq),
  FOREIGN KEY (warehouse_id,batch_id)
    REFERENCES movement_batch(warehouse_id,id),
  FOREIGN KEY (warehouse_id,location_id,sku_id) REFERENCES stock_balance,
  CHECK (after_book = before_book + delta_book),
  CHECK (after_blocked = before_blocked + delta_blocked),
  CHECK (before_book >= before_blocked AND after_book >= after_blocked),
  CHECK (delta_book <> 0 OR delta_blocked <> 0)
);

CREATE TABLE command_dedup (
  actor_id token_text NOT NULL,
  action token_text NOT NULL,
  request_key uuid NOT NULL,
  request_hash token_text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  response_status integer,
  response_body jsonb,
  PRIMARY KEY (actor_id,action,request_key),
  CHECK ((response_status IS NULL) = (response_body IS NULL))
);
CREATE TABLE audit_event (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  warehouse_id bigint REFERENCES warehouse(id),
  actor_id token_text NOT NULL,
  action token_text NOT NULL,
  entity_type token_text NOT NULL,
  entity_id token_text NOT NULL,
  request_id uuid NOT NULL,
  happened_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  before_state jsonb,
  after_state jsonb,
  reason text
);
CREATE INDEX receipt_queue ON receipt(warehouse_id,status,created_at DESC,id);
CREATE INDEX shipment_queue ON shipment(warehouse_id,status,created_at DESC,id);
CREATE INDEX count_work ON count_item(warehouse_id,assigned_to,status);
CREATE INDEX stock_by_sku ON stock_balance(warehouse_id,sku_id,location_id);
CREATE INDEX active_hold_by_stock
  ON reservation(warehouse_id,location_id,sku_id) WHERE status = 'ACTIVE';
CREATE INDEX ledger_period ON movement_batch(warehouse_id,posted_at,id);
CREATE INDEX approval_queue ON approval(warehouse_id,level_code,submitted_at)
  WHERE status = 'PENDING';
CREATE INDEX audit_target
  ON audit_event(warehouse_id,entity_type,entity_id,happened_at);
CREATE INDEX import_expiry ON import_preview(expires_at)
  WHERE applied_at IS NULL;
