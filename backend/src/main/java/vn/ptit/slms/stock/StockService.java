package vn.ptit.slms.stock;

import static vn.ptit.slms.common.Db.*;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ptit.slms.common.*;
import vn.ptit.slms.security.AccessGuard;

@Service
public class StockService {

    private final Db db;
    private final AccessGuard guard;

    public StockService(Db d, AccessGuard g) {
        db = d;
        guard = g;
    }

    public record Key(long location, long sku) implements Comparable<Key> {
        public int compareTo(Key o) {
            int c = Long.compare(sku, o.sku);
            return c != 0 ? c : Long.compare(location, o.location);
        }
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-06
     * Use case: UC-IN-005, UC-OUT-001, UC-OUT-005, UC-CNT-003, UC-CNT-006
     *
     * Khóa cấu hình kho, SKU và các số dư cần cập nhật trong giao dịch hiện tại.
     * Khóa theo thứ tự SKU rồi vị trí để giảm deadlock; chỉ nhận kho, SKU và ô BIN đang hoạt động.
     * Bên gọi phải mở giao dịch.
     */
    public void lock(long w, Collection<Key> keys) {
        db.one("select id from warehouse where id=? and active for share", w);
        for (long sku : keys.stream().map(Key::sku).distinct().sorted().toList())
            db.one("select id from sku where id=? and active for share", sku);
        for (long location : keys.stream().map(Key::location).distinct().sorted().toList())
            db.one("select id from location where id=? and warehouse_id=? and active and kind='BIN' for share",
                    location, w);
        for (long sku : keys.stream().map(Key::sku).distinct().sorted().toList()) {
            db.run("insert into warehouse_sku(warehouse_id,sku_id) values (?,?) on conflict do nothing",
                    w, sku);
            db.one("select * from warehouse_sku where warehouse_id=? and sku_id=? for update", w,
                    sku);
        }
        for (Key k : keys.stream().distinct().sorted().toList()) {
            Problem.require(db.id(
                    "select count(*) from location l join sku s on s.id=? where l.id=? and l.warehouse_id=? and l.kind='BIN' and l.active and s.active",
                    k.sku, k.location, w) > 0, "INACTIVE_STOCK_KEY",
                    "Vị trí/SKU không hợp lệ hoặc đã ngừng dùng.");
            db.run("insert into stock_balance(warehouse_id,location_id,sku_id) values (?,?,?) on conflict do nothing",
                    w, k.location, k.sku);
            db.one("select * from stock_balance where warehouse_id=? and location_id=? and sku_id=? for update",
                    w, k.location, k.sku);
        }
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-06
     * Use case: UC-STK-001
     *
     * Đọc số dư của một cặp vị trí và SKU trong kho.
     * Các thao tác sửa số dư phải khóa cặp tồn qua lock trước khi đọc để tính lượng mới.
     */
    public Map<String, Object> balance(long w, Key k) {
        return db.one(
                "select * from stock_balance where warehouse_id=? and location_id=? and sku_id=?",
                w, k.location, k.sku);
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-07
     * Use case: UC-IN-005, UC-OUT-001, UC-OUT-003, UC-OUT-005, UC-CNT-003
     *
     * Kiểm tra cặp tồn không có dòng kiểm kê đang đếm.
     * Chặn thao tác khi còn dòng COUNTING tại cùng kho, vị trí và SKU.
     */
    public void unlocked(long w, Key k) {
        Problem.require(db.id(
                "select count(*) from count_item where warehouse_id=? and location_id=? and sku_id=? and status='COUNTING'",
                w, k.location, k.sku) == 0, "COUNT_LOCKED", "Vị trí/SKU đang được kiểm đếm.");
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-07
     * Use case: UC-OUT-001, UC-OUT-004, UC-OUT-005, UC-OUT-006
     *
     * Tăng hoặc giải phóng lượng giữ hàng và cập nhật cảnh báo tồn thấp.
     * Bên gọi đã khóa tồn; lượng giữ và lượng khả dụng sau thay đổi phải không âm.
     */
    public void held(long w, Key k, BigDecimal delta) {
        var b = balance(w, k);
        Problem.require(
                dec(b.get("held_qty")).add(delta).signum() >= 0
                        && dec(b.get("available_qty")).subtract(delta).signum() >= 0,
                "INSUFFICIENT_AVAILABLE", "Không đủ lượng khả dụng để giữ hàng.");
        db.run("update stock_balance set held_qty=held_qty+? where warehouse_id=? and location_id=? and sku_id=?",
                delta, w, k.location, k.sku);
        alert(w, k.sku);
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-07
     * Use case: UC-IN-005, UC-OUT-005, UC-CNT-006, UC-IN-007
     *
     * Tạo lô biến động gắn với chứng từ nhập, xuất hoặc điều chỉnh.
     * Ghi người và thời điểm chốt; bên gọi thực hiện cùng giao dịch với các dòng biến động.
     */
    public long batch(long w, String source, long id) {
        String col = switch (source) {
        case "IN" -> "receipt_id";
        case "OUT" -> "shipment_id";
        case "ADJ" -> "adjustment_id";
        default -> throw new IllegalArgumentException();
        };
        return db.id(
                "insert into movement_batch(warehouse_id," + col
                        + ",posted_at,posted_by) values (?,?,clock_timestamp(),?) returning id",
                w, id, guard.actor());
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-08
     * Use case: UC-IN-005, UC-OUT-005, UC-CNT-006, UC-IN-007
     *
     * Ghi số dư mới và dòng thẻ kho cho một cặp vị trí và SKU.
     * Bên gọi đã khóa tồn; không ghi khi đang đếm hoặc làm âm tồn/khả dụng. Tăng số thứ tự biến
     * động và cập nhật cảnh báo trong cùng giao dịch.
     */
    public void movement(long w, long batch, Key k, BigDecimal book, BigDecimal blocked) {
        unlocked(w, k);
        var b = balance(w, k);
        var before = dec(b.get("book_qty"));
        var beforeBlocked = dec(b.get("blocked_qty"));
        var after = before.add(book);
        var afterBlocked = beforeBlocked.add(blocked);
        Problem.require(
                after.signum() >= 0 && afterBlocked.signum() >= 0
                        && after.compareTo(afterBlocked.add(dec(b.get("held_qty")))) >= 0,
                "NEGATIVE_AFTER_POST", "Sau ghi sổ, tồn hoặc khả dụng sẽ âm.");
        long seq = lng(b.get("last_seq")) + 1;
        db.run("update stock_balance set book_qty=?,blocked_qty=?,last_seq=?,last_posted_at=clock_timestamp() where warehouse_id=? and location_id=? and sku_id=?",
                after, afterBlocked, seq, w, k.location, k.sku);
        db.run("insert into movement_line(batch_id,warehouse_id,location_id,sku_id,seq,before_book,delta_book,after_book,before_blocked,delta_blocked,after_blocked) values (?,?,?,?,?,?,?,?,?,?,?)",
                batch, w, k.location, k.sku, seq, before, book, after, beforeBlocked, blocked,
                afterBlocked);
        alert(w, k.sku);
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-09
     * Use case: UC-STK-004
     *
     * Đồng bộ cảnh báo theo tổng lượng khả dụng của SKU trong kho.
     * Chỉ đánh giá khi đã đặt ngưỡng; mở/cập nhật cảnh báo dưới ngưỡng và đóng khi lượng khả dụng
     * đạt ngưỡng.
     */
    public void alert(long w, long sku) {
        var c = db.one("select min_qty from warehouse_sku where warehouse_id=? and sku_id=?", w,
                sku);
        if (c.get("min_qty") == null)
            return;
        BigDecimal available = db.decimal(
                "select coalesce(sum(available_qty),0) from stock_balance where warehouse_id=? and sku_id=?",
                w, sku);
        var threshold = dec(c.get("min_qty"));
        if (available.compareTo(threshold) < 0) {
            db.run("insert into low_stock_alert(warehouse_id,sku_id,status,observed_qty,threshold_qty) values (?,?,'OPEN',?,?) on conflict (warehouse_id,sku_id) where status<>'RESOLVED' do update set observed_qty=excluded.observed_qty,threshold_qty=excluded.threshold_qty,changed_at=clock_timestamp()",
                    w, sku, available, threshold);
        } else
            db.run("update low_stock_alert set status='RESOLVED',changed_at=clock_timestamp() where warehouse_id=? and sku_id=? and status<>'RESOLVED'",
                    w, sku);
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-10
     * Use case: UC-STK-004
     *
     * Đánh giá lại các cảnh báo có cấu hình ngưỡng theo lịch.
     * Khóa cấu hình theo thứ tự kho, SKU và xử lý trong một giao dịch.
     */
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void refreshAlerts() {
        for (var r : db.rows(
                "select warehouse_id,sku_id from warehouse_sku where min_qty is not null order by warehouse_id,sku_id for update"))
            alert(lng(r.get("warehouse_id")), lng(r.get("sku_id")));
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-08
     * Use case: UC-STK-001, UC-STK-002, UC-STK-004
     *
     * Tra cứu số dư, thẻ kho hoặc cảnh báo theo kho và phân trang.
     * Kiểm tra quyền kho trước khi đọc; loại truy vấn chỉ nhận balances, ledger hoặc alerts.
     */
    public Object query(long w, String kind, long after, int limit) {
        guard.warehouse(w);
        return switch (kind) {
        case "balances" -> db.rows(
                "select b.*,l.code location_code,s.code sku_code,s.name sku_name,u.symbol from stock_balance b join location l on l.id=b.location_id join sku s on s.id=b.sku_id join uom u on u.id=s.uom_id where b.warehouse_id=? order by s.code,l.code limit ? offset ?",
                w, limit, after);
        case "ledger" -> db.rows(
                "select m.*,b.id batch_id,b.posted_at,b.receipt_id,b.shipment_id,b.adjustment_id,s.code sku_code,l.code location_code from movement_line m join movement_batch b on b.id=m.batch_id join sku s on s.id=m.sku_id join location l on l.id=m.location_id where m.warehouse_id=? order by b.id desc,m.sku_id,m.location_id limit ? offset ?",
                w, limit, after);
        case "alerts" -> db.rows(
                "select a.*,s.code sku_code,s.name sku_name from low_stock_alert a join sku s on s.id=a.sku_id where a.warehouse_id=? order by a.id desc limit ? offset ?",
                w, limit, after);
        default -> throw new IllegalArgumentException();
        };
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-09
     * Use case: UC-STK-003
     *
     * Thiết lập ngưỡng tồn tối thiểu cho SKU trong kho.
     * Chỉ ACT02 được đặt ngưỡng; lượng không âm, tối đa 3 chữ số lẻ và đánh giá lại cảnh báo ngay
     * sau khi lưu.
     */
    public Object threshold(long w, Map<String, Object> p) {
        guard.roles("ACT02");
        long sku = lng(p.get("skuId"));
        db.run("insert into warehouse_sku(warehouse_id,sku_id,min_qty,threshold_set_by,threshold_set_at) values (?,?,?,?,clock_timestamp()) on conflict(warehouse_id,sku_id) do update set min_qty=excluded.min_qty,threshold_set_by=excluded.threshold_set_by,threshold_set_at=excluded.threshold_set_at,version=warehouse_sku.version+1",
                w, sku, qty(p.get("minQty")), guard.actor());
        db.one("select * from warehouse_sku where warehouse_id=? and sku_id=? for update", w, sku);
        alert(w, sku);
        return Map.of("ok", true);
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-10
     * Use case: UC-STK-004
     *
     * Ghi nội dung xử lý và người tiếp nhận cảnh báo tồn thấp.
     * Chỉ ACT02 được xử lý đúng phiên bản; không sửa cảnh báo RESOLVED. Tiếp nhận chuyển trạng thái
     * IN_PROGRESS.
     */
    public Object handle(long w, long id, Map<String, Object> p) {
        guard.roles("ACT02");
        var a = db.one("select * from low_stock_alert where warehouse_id=? and id=? for update", w,
                id);
        Problem.require(lng(a.get("version")) == lng(p.get("version")), "STALE_VERSION",
                "Tải lại cảnh báo.");
        Problem.require(!a.get("status").equals("RESOLVED"), "ALREADY_RESOLVED",
                "Cảnh báo đã đóng.");
        db.run("update low_stock_alert set status='IN_PROGRESS',resolution=?,handled_by=?,changed_at=clock_timestamp(),version=version+1 where id=?",
                required(p, "resolution"), guard.actor(), id);
        return Map.of("id", id, "status", "IN_PROGRESS");
    }
}
