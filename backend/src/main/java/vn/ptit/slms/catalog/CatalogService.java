package vn.ptit.slms.catalog;

import static vn.ptit.slms.common.Db.*;

import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ptit.slms.common.*;
import vn.ptit.slms.security.AccessGuard;

@Service
public class CatalogService {

    private final Db db;
    private final AccessGuard guard;

    public CatalogService(Db d, AccessGuard g) {
        db = d;
        guard = g;
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-05
     * Use case: UC-CAT-002, UC-CAT-004
     *
     * Tra cứu danh mục và người dùng của kho được cấp quyền.
     * Danh mục SKU, đơn vị và quy đổi dùng chung; vị trí và người dùng lọc theo kho.
     */
    public Object list(long w, String kind) {
        guard.warehouse(w);
        return switch (kind) {
        case "locations" -> db.rows("select * from location where warehouse_id=? order by code", w);
        case "skus" ->
            db.rows("select s.*,u.symbol from sku s join uom u on u.id=s.uom_id order by s.code");
        case "uoms" -> db.rows("select * from uom order by code");
        case "conversions" -> db.rows("select * from uom_conversion order by sku_id,uom_id");
        case "users" -> db.rows(
                "select a.username,a.role from app_user a join user_warehouse u on u.username=a.username where u.warehouse_id=? and a.active order by a.username",
                w);
        default -> throw new IllegalArgumentException();
        };
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-04
     * Use case: UC-CAT-001
     *
     * Tạo kho và cấp quyền kho cho các tài khoản đang hoạt động.
     * Chỉ ACT02 được tạo kho; việc tạo kho và cấp quyền nằm trong cùng giao dịch.
     */
    @Transactional
    public Object warehouse(Map<String, Object> p) {
        guard.roles("ACT02");
        long id = db.id("insert into warehouse(code,name,description) values (?,?,?) returning id",
                required(p, "code"), required(p, "name"), text(p, "description"));
        db.run("insert into user_warehouse(username,warehouse_id) select username,? from app_user where active",
                id);
        return Map.of("id", id);
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-05
     * Use case: UC-CAT-001
     *
     * Cập nhật thông tin và trạng thái hoạt động của kho.
     * Giữ nguyên mã kho, kiểm tra phiên bản và chỉ ngừng kho khi hết tồn, vị trí hoạt động, hồ sơ
     * mở.
     */
    public Object updateWarehouse(long w, Map<String, Object> p) {
        guard.roles("ACT02");
        var old = db.one("select * from warehouse where id=? for update", w);
        Problem.require(lng(old.get("version")) == lng(p.get("version")), "STALE_VERSION",
                "Kho đã được sửa.");
        Problem.require(old.get("code").equals(required(p, "code")), "IMMUTABLE_CODE",
                "Không thay đổi mã kho.");
        boolean active = !Boolean.FALSE.equals(p.get("active"));
        if (!active) {
            Problem.require(db.id(
                    "select (select count(*) from location where warehouse_id=? and active)+(select count(*) from stock_balance where warehouse_id=? and (book_qty>0 or held_qty>0))+(select count(*) from receipt where warehouse_id=? and status not in ('POSTED','CANCELLED'))+(select count(*) from shipment where warehouse_id=? and status not in ('POSTED','CANCELLED'))+(select count(*) from count_session where warehouse_id=? and status<>'CLOSED')",
                    w, w, w, w, w) == 0, "IN_USE", "Kho còn vị trí hoạt động, tồn hoặc hồ sơ mở.");
        }
        db.run("update warehouse set name=?,description=?,active=?,version=version+1 where id=?",
                required(p, "name"), text(p, "description"), active, w);
        return Map.of("id", w, "ok", true);
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-06
     * Use case: UC-CAT-002, UC-CAT-004
     *
     * Tạo hoặc cập nhật vị trí, hàng hóa, đơn vị tính và hệ số quy đổi.
     * Chỉ ACT02 được sửa; giữ mã, cấu trúc vị trí và đơn vị cơ sở đã tạo. Cây vị trí tuân theo AREA
     * → AISLE → BIN.
     */
    public Object save(long w, String kind, Map<String, Object> p) {
        guard.roles("ACT02");
        if (kind.equals("conversions")) {
            long sku = lng(p.get("skuId")), uom = lng(p.get("uomId"));
            var factor = new BigDecimal(required(p, "factor"));
            Problem.require(factor.signum() > 0 && factor.scale() <= 6, "FACTOR",
                    "Hệ số phải dương, tối đa 6 chữ số lẻ.");
            db.one("select * from sku where id=? for update", sku);
            db.run("insert into uom_conversion(sku_id,uom_id,factor) values (?,?,?) on conflict(sku_id,uom_id) do update set factor=excluded.factor,active=true",
                    sku, uom, factor);
            return Map.of("ok", true);
        }
        String table = switch (kind) {
        case "locations" -> "location";
        case "skus" -> "sku";
        case "uoms" -> "uom";
        default -> throw new IllegalArgumentException();
        };
        boolean update = p.get("id") != null;
        long id = update ? lng(p.get("id")) : 0;
        Map<String, Object> old = null;
        if (update) {
            old = db.one("select * from " + table + " where id=?"
                    + (table.equals("location") ? " and warehouse_id=" + w : "") + " for update",
                    id);
            Problem.require(lng(old.get("version")) == lng(p.get("version")), "STALE_VERSION",
                    "Dữ liệu đã được sửa.");
            Problem.require(old.get("code").equals(required(p, "code")), "IMMUTABLE_CODE",
                    "Mã đã tạo không được đổi.");
        }
        boolean active = !Boolean.FALSE.equals(p.get("active"));
        if (update && !active) {
            String q = table.equals("location")
                    ? "select count(*) from stock_balance where location_id=? and (book_qty>0 or held_qty>0)"
                    : table.equals("sku")
                            ? "select count(*) from stock_balance where sku_id=? and (book_qty>0 or held_qty>0)"
                            : "select count(*) from sku where uom_id=? and active";
            Problem.require(db.id(q, id) == 0, "IN_USE",
                    "Danh mục còn tồn hoặc đang được sử dụng.");
            if (table.equals("location"))
                Problem.require(db.id("select count(*) from location where parent_id=? and active",
                        id) == 0, "IN_USE", "Vị trí còn nút con đang dùng.");
            if (!table.equals("uom")) {
                String col = table.equals("sku") ? "sku_id" : "location_id";
                Problem.require(db.id(
                        "select (select count(*) from receipt_line l join receipt r on r.id=l.receipt_id where l."
                                + col
                                + "=? and r.status not in ('POSTED','CANCELLED'))+(select count(*) from count_item where "
                                + col + "=? and status<>'CLOSED')",
                        id, id) == 0, "IN_USE", "Danh mục còn phiếu nhập/kiểm kê mở.");
            }
        }
        if (table.equals("location")) {
            String k = required(p, "kind");
            Long parent = p.get("parentId") == null || p.get("parentId").toString().isBlank() ? null
                    : lng(p.get("parentId"));
            if (k.equals("AREA"))
                Problem.require(parent == null, "HIERARCHY", "Khu vực không có cha.");
            else {
                var par = db.one(
                        "select * from location where id=? and warehouse_id=? and active for update",
                        parent, w);
                Problem.require(
                        (k.equals("AISLE") && par.get("kind").equals("AREA"))
                                || (k.equals("BIN") && par.get("kind").equals("AISLE")),
                        "HIERARCHY", "Cây vị trí phải AREA → AISLE → BIN.");
            }
            if (update) {
                Problem.require(
                        Objects.equals(old.get("parent_id"), parent) && old.get("kind").equals(k),
                        "IMMUTABLE_STRUCTURE", "Tạo vị trí mới nếu cần đổi cấp hoặc cha.");
                db.run("update location set name=?,active=?,version=version+1 where id=?",
                        required(p, "name"), active, id);
            } else
                id = db.id(
                        "insert into location(warehouse_id,parent_id,kind,code,name) values (?,?,?,?,?) returning id",
                        w, parent, k, required(p, "code"), required(p, "name"));
        } else if (table.equals("sku")) {
            long uom = lng(p.get("uomId"));
            db.one("select * from uom where id=? and active for update", uom);
            if (update) {
                Problem.require(lng(old.get("uom_id")) == uom, "IMMUTABLE_UOM",
                        "Đơn vị cơ sở không được đổi.");
                db.run("update sku set name=?,barcode=nullif(?,''),active=?,version=version+1 where id=?",
                        required(p, "name"), text(p, "barcode"), active, id);
            } else
                id = db.id(
                        "insert into sku(code,name,barcode,uom_id) values (?,?,nullif(?,''),?) returning id",
                        required(p, "code"), required(p, "name"), text(p, "barcode"), uom);
        } else {
            if (update)
                db.run("update uom set name=?,symbol=?,active=?,version=version+1 where id=?",
                        required(p, "name"), text(p, "symbol"), active, id);
            else
                id = db.id("insert into uom(code,name,symbol) values (?,?,?) returning id",
                        required(p, "code"), required(p, "name"), text(p, "symbol"));
        }
        return Map.of("id", id, "ok", true);
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-05
     * Use case: UC-CAT-004, UC-IN-001, UC-OUT-001
     *
     * Lấy hệ số quy đổi từ đơn vị giao dịch sang đơn vị cơ sở của SKU.
     * SKU phải hoạt động; đơn vị cơ sở có hệ số 1, đơn vị khác cần cấu hình quy đổi đang hoạt động.
     */
    public BigDecimal factor(long sku, long uom) {
        var s = db.one("select uom_id from sku where id=? and active for share", sku);
        if (lng(s.get("uom_id")) == uom)
            return BigDecimal.ONE;
        return dec(db.one(
                "select factor from uom_conversion where sku_id=? and uom_id=? and active for share",
                sku, uom).get("factor"));
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-05
     * Use case: UC-CAT-004, UC-IN-001, UC-OUT-001
     *
     * Quy đổi số lượng giao dịch về đơn vị cơ sở.
     * Kết quả phải biểu diễn chính xác với 3 chữ số thập phân; không tự làm tròn.
     */
    public BigDecimal base(BigDecimal entered, BigDecimal factor) {
        var result = entered.multiply(factor);
        try {
            return result.setScale(3, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new Problem("ROUNDING_REQUIRED",
                    "Quy đổi vượt 3 chữ số lẻ; hãy đổi số lượng hoặc đơn vị.");
        }
    }
}
