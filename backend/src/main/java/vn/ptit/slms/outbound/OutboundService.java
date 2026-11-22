package vn.ptit.slms.outbound;

import static vn.ptit.slms.common.Db.*;

import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import vn.ptit.slms.approval.*;
import vn.ptit.slms.catalog.*;
import vn.ptit.slms.common.*;
import vn.ptit.slms.security.*;
import vn.ptit.slms.stock.*;

@Service
public class OutboundService {

    private final Db db;
    private final AccessGuard g;
    private final CatalogService catalog;
    private final StockService stock;
    private final ApprovalService approval;
    private final Json json;

    public OutboundService(Db d, AccessGuard g, CatalogService c, StockService s, ApprovalService a,
            Json j) {
        db = d;
        this.g = g;
        catalog = c;
        stock = s;
        approval = a;
        json = j;
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-09
     * Use case: UC-OUT-001
     *
     * Liệt kê tối đa 200 phiếu xuất mới nhất trong kho.
     * Người dùng phải được cấp quyền truy cập kho.
     */
    public Object list(long w) {
        g.warehouse(w);
        return db.rows("select * from shipment where warehouse_id=? order by id desc limit 200", w);
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-09
     * Use case: UC-OUT-001, UC-OUT-003
     *
     * Đọc phiếu xuất, dòng yêu cầu và các lần giữ hàng.
     * Phiếu phải thuộc kho được cấp quyền; dữ liệu giữ hàng dùng để theo dõi phân bổ và soạn.
     */
    public Map<String, Object> detail(long w, long id) {
        g.warehouse(w);
        var r = new LinkedHashMap<>(
                db.one("select * from shipment where warehouse_id=? and id=?", w, id));
        r.put("lines", db.rows(
                "select l.*,s.code sku_code from shipment_line l join sku s on s.id=l.sku_id where shipment_id=? order by line_no",
                id));
        r.put("reservations",
                db.rows("select * from reservation where shipment_id=? order by id", id));
        return r;
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-08
     * Use case: UC-OUT-001
     *
     * Lập phiếu xuất nháp và quy đổi lượng yêu cầu về đơn vị cơ sở.
     * Chỉ ACT03 được lập; cần ít nhất một dòng có lượng dương và quy đổi hợp lệ.
     */
    @SuppressWarnings("unchecked")
    public Object create(long w, Map<String, Object> p) {
        g.roles("ACT03");
        var lines = (List<Map<String, Object>>) p.get("lines");
        Problem.require(lines != null && !lines.isEmpty(), "LINES", "Cần dòng yêu cầu xuất.");
        long id = db.id(
                "insert into shipment(warehouse_id,code,source_type,source_ref,status,created_by) values (?,?,?,?,'DRAFT',?) returning id",
                w, "PX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                required(p, "sourceType"), required(p, "sourceRef"), g.actor());
        int n = 0;
        for (var l : lines) {
            long sku = lng(l.get("skuId")), uom = lng(l.get("uomId"));
            var factor = catalog.factor(sku, uom);
            var qty = qty(l.get("qty"));
            Problem.require(qty.signum() > 0, "QUANTITY", "Số lượng phải dương.");
            db.run("insert into shipment_line(shipment_id,line_no,warehouse_id,sku_id,uom_id,requested_qty,entered_qty,conversion_factor) values (?,?,?,?,?,?,?,?)",
                    id, ++n, w, sku, uom, catalog.base(qty, factor), qty, factor);
        }
        return detail(w, id);
    }

    private List<Map<String, Object>> holds(long id) {
        return db.rows(
                "select * from reservation where shipment_id=? and status='ACTIVE' order by sku_id,location_id",
                id);
    }

    private StockService.Key key(Map<String, Object> h) {
        return new StockService.Key(lng(h.get("location_id")), lng(h.get("sku_id")));
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-10
     * Use case: UC-OUT-004, UC-OUT-006
     *
     * Giải phóng các phân bổ giữ hàng đang hoạt động của phiếu xuất.
     * Bên gọi phải khóa tồn trước; hoàn lại lượng giữ và đánh dấu từng phân bổ RELEASED.
     */
    private void release(long w, long id) {
        for (var h : holds(id)) {
            stock.held(w, key(h), dec(h.get("qty_held")).negate());
            db.run("update reservation set status='RELEASED',ended_at=clock_timestamp() where id=?",
                    h.get("id"));
        }
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-11
     * Use case: UC-OUT-001, UC-OUT-002, UC-OUT-003, UC-OUT-004, UC-OUT-005, UC-OUT-006
     *
     * Xử lý phân bổ, duyệt, soạn, bàn giao hoặc hủy phiếu xuất.
     * Kiểm tra phiên bản; phân bổ phải đủ từng dòng, soạn không vượt lượng giữ và chỉ bàn giao khi
     * PICKED. Bên gọi dùng giao dịch CommandBus.
     */
    @SuppressWarnings("unchecked")
    public Object action(long w, long id, String action, Map<String, Object> p) {
        var r = db.one("select * from shipment where warehouse_id=? and id=? for update", w, id);
        long version = lng(r.get("version"));
        Problem.require(version == lng(p.get("version")), "STALE_VERSION",
                "Phiếu xuất đã thay đổi.");
        String state = r.get("status").toString(), next = state;
        var lines = db.rows("select * from shipment_line where shipment_id=? order by line_no", id);
        var old = holds(id);
        switch (action) {
        // UC-OUT-001: Sửa lượng theo đơn vị giao dịch trên các dòng hiện có.
        case "update": {
            g.roles("ACT03");
            Problem.require(Set.of("DRAFT", "RETURNED").contains(state), "STATE",
                    "Chỉ sửa nháp hoặc phiếu trả lại.");
            @SuppressWarnings("unchecked")
            var updates = (List<Map<String, Object>>) p.get("lines");
            Problem.require(updates != null && updates.size() == lines.size(), "LINES",
                    "Cần đủ các dòng.");
            Set<Long> seen = new HashSet<>();
            for (var l : updates) {
                long no = lng(l.get("lineNo"));
                Problem.require(seen.add(no), "LINES", "Dòng bị lặp.");
                var src = db.one("select * from shipment_line where shipment_id=? and line_no=?",
                        id, no);
                BigDecimal entered = qty(l.get("qty"));
                Problem.require(entered.signum() > 0, "QUANTITY", "Số lượng phải dương.");
                db.run("update shipment_line set entered_qty=?,requested_qty=? where shipment_id=? and line_no=?",
                        entered, catalog.base(entered, dec(src.get("conversion_factor"))), id, no);
            }
            db.run("update shipment set source_type=?,source_ref=? where id=?",
                    required(p, "sourceType"), required(p, "sourceRef"), id);
            next = "RETURNED";
            break;
        }
        // UC-OUT-001 / UC-OUT-004: Khóa cả vị trí cũ và mới trước khi đổi phân bổ.
        case "reserve":
        case "reallocate": {
            g.roles("ACT03", "ACT02");
            Problem.require(Set.of("DRAFT", "RETURNED", "ISSUE").contains(state), "STATE",
                    "Phiếu không thể phân bổ tại bước này.");
            if (old.stream().anyMatch(h -> dec(h.get("qty_picked")).signum() > 0))
                Problem.require(Boolean.TRUE.equals(p.get("returnedPicked")), "RETURN_PICKED",
                        "Cần xác nhận hoàn trả hàng đã soạn.");
            var alloc = (List<Map<String, Object>>) p.get("allocations");
            Problem.require(alloc != null && !alloc.isEmpty(), "ALLOCATION",
                    "Cần phân bổ dòng xuất.");
            List<StockService.Key> keys = new ArrayList<>(old.stream().map(this::key).toList());
            Map<Long, BigDecimal> sums = new HashMap<>();
            Set<String> seen = new HashSet<>();
            for (var a : alloc) {
                long no = lng(a.get("lineNo"));
                var l = lines.stream().filter(x -> lng(x.get("line_no")) == no).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException());
                long loc = lng(a.get("locationId"));
                Problem.require(seen.add(no + ":" + loc), "ALLOCATION",
                        "Phân bổ trùng dòng/vị trí.");
                var qty = qty(a.get("qty"));
                Problem.require(qty.signum() > 0, "QUANTITY", "Lượng giữ phải dương.");
                sums.merge(no, qty, BigDecimal::add);
                keys.add(new StockService.Key(loc, lng(l.get("sku_id"))));
            }
            for (var l : lines)
                Problem.require(
                        sums.getOrDefault(lng(l.get("line_no")), BigDecimal.ZERO)
                                .compareTo(dec(l.get("requested_qty"))) == 0,
                        "ALLOCATION", "Tổng phân bổ phải bằng lượng yêu cầu mỗi dòng.");
            stock.lock(w, keys);
            release(w, id);
            for (var a : alloc) {
                var l = lines.stream().filter(x -> lng(x.get("line_no")) == lng(a.get("lineNo")))
                        .findFirst().orElseThrow();
                var k = new StockService.Key(lng(a.get("locationId")), lng(l.get("sku_id")));
                stock.unlocked(w, k);
                stock.held(w, k, qty(a.get("qty")));
                db.run("insert into reservation(warehouse_id,shipment_id,line_no,location_id,sku_id,qty_held,status) values (?,?,?,?,?,?,'ACTIVE')",
                        w, id, lng(a.get("lineNo")), k.location(), k.sku(), qty(a.get("qty")));
            }
            approval.submit(w, "shipment", id, version + 1, r.get("created_by").toString(),
                    lines.stream().map(l -> dec(l.get("requested_qty"))).reduce(BigDecimal.ZERO,
                            BigDecimal::add),
                    detail(w, id), false);
            next = "PENDING";
            break;
        }
        // UC-OUT-002: Chỉ hồ sơ chờ đúng cấp duyệt mới được quyết định.
        case "decision": {
            Problem.require(state.equals("PENDING"), "STATE", "Phiếu không chờ duyệt.");
            boolean accept = Boolean.TRUE.equals(p.get("accept"));
            approval.decide(w, "shipment", id, version, accept, text(p, "reason"));
            next = accept ? "READY" : "RETURNED";
            break;
        }
        // UC-OUT-003: Lượng gửi lên là tổng đã soạn, kèm mã hàng và vị trí khớp phân bổ.
        case "pick": {
            g.roles("ACT03");
            Problem.require(Set.of("READY", "PICKING").contains(state), "STATE",
                    "Phiếu chưa được phép soạn.");
            stock.lock(w, old.stream().map(this::key).toList());
            var picks = (List<Map<String, Object>>) p.get("picks");
            Problem.require(picks != null && !picks.isEmpty(), "PICKS", "Thiếu kết quả soạn.");
            for (var pick : picks) {
                var h = old.stream().filter(x -> lng(x.get("id")) == lng(pick.get("reservationId")))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException());
                stock.unlocked(w, key(h));
                var identity = db.one(
                        "select s.code,s.barcode,l.code location_code from sku s cross join location l where s.id=? and l.id=?",
                        h.get("sku_id"), h.get("location_id"));
                String scan = required(pick, "scanCode");
                Problem.require(
                        scan.equals(identity.get("code")) || scan.equals(identity.get("barcode")),
                        "SCAN_MISMATCH", "Mã hàng không khớp phân bổ.");
                Problem.require(
                        required(pick, "locationCode").equals(identity.get("location_code")),
                        "SCAN_MISMATCH", "Mã vị trí không khớp phân bổ.");
                var q = qty(pick.get("qty"));
                Problem.require(q.compareTo(dec(h.get("qty_held"))) <= 0, "OVER_PICK",
                        "Lượng soạn vượt lượng giữ.");
                db.run("update reservation set qty_picked=? where id=?", q, h.get("id"));
            }
            next = holds(id).stream().allMatch(
                    h -> dec(h.get("qty_picked")).compareTo(dec(h.get("qty_held"))) == 0) ? "PICKED"
                            : "PICKING";
            break;
        }
        // UC-OUT-004: Ghi lý do và chứng cứ để xử lý thiếu hoặc sai hàng.
        case "issues":
            g.roles("ACT03");
            Problem.require(Set.of("READY", "PICKING", "PICKED").contains(state), "STATE",
                    "Không thể báo lỗi ở trạng thái này.");
            db.run("update shipment set issue_reason=?,issue_evidence=?::jsonb where id=?",
                    required(p, "reason"), json.write(p.getOrDefault("evidenceIds", List.of())),
                    id);
            next = "ISSUE";
            break;
        // UC-OUT-005: Giải phóng lượng giữ, trừ tồn và lưu bản chụp bàn giao trong cùng lệnh.
        case "confirm": {
            g.roles("ACT03");
            Problem.require(state.equals("PICKED") && !old.isEmpty(), "STATE",
                    "Cần soạn đủ hàng trước bàn giao.");
            required(p, "receiverName");
            stock.lock(w, old.stream().map(this::key).toList());
            long batch = stock.batch(w, "OUT", id);
            Map<StockService.Key, BigDecimal> totals = new TreeMap<>();
            for (var h : old) {
                Problem.require(dec(h.get("qty_held")).compareTo(dec(h.get("qty_picked"))) == 0,
                        "PICK_INCOMPLETE", "Chưa soạn đủ.");
                stock.unlocked(w, key(h));
                stock.held(w, key(h), dec(h.get("qty_held")).negate());
                totals.merge(key(h), dec(h.get("qty_held")), BigDecimal::add);
                db.run("update reservation set status='CONSUMED',ended_at=clock_timestamp() where id=?",
                        h.get("id"));
            }
            for (var e : totals.entrySet())
                stock.movement(w, batch, e.getKey(), e.getValue().negate(), BigDecimal.ZERO);
            db.run("insert into handover(shipment_id,handed_at,handed_by,receiver_name,source_ref,snapshot) values (?,clock_timestamp(),?,?,?,?::jsonb)",
                    id, g.actor(), required(p, "receiverName"), text(p, "sourceRef"),
                    json.write(Map.of("shipment", detail(w, id), "receiverName",
                            required(p, "receiverName"))));
            next = "POSTED";
            break;
        }
        // UC-OUT-006: Hàng đã soạn phải xác nhận hoàn trả trước khi giải phóng giữ hàng.
        case "cancel":
            g.roles("ACT02", "ACT03");
            required(p, "reason");
            Problem.require(!Set.of("POSTED", "CANCELLED").contains(state), "STATE",
                    "Phiếu đã hoàn tất/hủy.");
            if (old.stream().anyMatch(h -> dec(h.get("qty_picked")).signum() > 0))
                Problem.require(Boolean.TRUE.equals(p.get("returnedPicked")), "RETURN_PICKED",
                        "Xác nhận đã hoàn trả hàng soạn.");
            stock.lock(w, old.stream().map(this::key).toList());
            release(w, id);
            approval.cancel("shipment", id);
            next = "CANCELLED";
            break;
        default:
            throw new IllegalArgumentException();
        }
        db.run("update shipment set status=?,version=version+1 where id=?", next, id);
        return detail(w, id);
    }
}
