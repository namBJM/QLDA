package vn.ptit.slms.file;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ptit.slms.common.*;
import vn.ptit.slms.security.*;

@Service
public class ReportService {

    private final Db db;
    private final AccessGuard g;

    public ReportService(Db d, AccessGuard g) {
        db = d;
        this.g = g;
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-15
     * Use case: UC-STK-001, UC-STK-002
     *
     * Tổng hợp nhập, xuất, điều chỉnh và tồn theo khoảng thời gian [from, to).
     * Chỉ ACT02/ACT09 được xem; from phải trước to. REPEATABLE_READ giữ dữ liệu nhất quán trong lần
     * tổng hợp.
     */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> report(long w, OffsetDateTime from, OffsetDateTime to) {
        g.warehouse(w);
        g.roles("ACT02", "ACT09");
        Problem.require(from.isBefore(to), "DATE_RANGE", "Ngày bắt đầu phải trước ngày kết thúc.");
        return db.rows(
                "select s.code sku_code,s.name sku_name,u.symbol,coalesce(sum(m.delta_book) filter(where b.posted_at<?),0) opening_qty,coalesce(sum(m.delta_book) filter(where b.posted_at>=? and b.receipt_id is not null),0) inbound_qty,-coalesce(sum(m.delta_book) filter(where b.posted_at>=? and b.shipment_id is not null),0) outbound_qty,coalesce(sum(m.delta_book) filter(where b.posted_at>=? and b.adjustment_id is not null),0) adjustment_qty,coalesce(sum(m.delta_book),0) closing_qty from movement_line m join movement_batch b on b.id=m.batch_id join sku s on s.id=m.sku_id join uom u on u.id=s.uom_id where b.warehouse_id=? and b.posted_at<? group by s.id,u.symbol order by s.code",
                from, from, from, from, w, to);
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-16
     * Use case: UC-STK-001, UC-STK-002
     *
     * Xuất dữ liệu báo cáo đã tổng hợp thành tệp XLSX.
     * Giữ thứ tự cột của dữ liệu đầu vào; ghi giá trị dạng chuỗi để giữ biểu diễn số gốc.
     */
    public byte[] excel(List<Map<String, Object>> data) {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var s = book.createSheet("NhapXuatTon");
            if (!data.isEmpty()) {
                var cols = new ArrayList<>(data.get(0).keySet());
                var header = s.createRow(0);
                for (int c = 0; c < cols.size(); c++)
                    header.createCell(c).setCellValue(cols.get(c));
                for (int i = 0; i < data.size(); i++) {
                    var row = s.createRow(i + 1);
                    for (int c = 0; c < cols.size(); c++) {
                        Object value = data.get(i).get(cols.get(c));
                        row.createCell(c).setCellValue(Objects.toString(value, ""));
                    }
                }
                for (int c = 0; c < cols.size(); c++)
                    s.autoSizeColumn(c);
            }
            book.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-17
     * Use case: UC-CNT-009, UC-OUT-007
     *
     * Đọc bản chụp biên bản kiểm kê hoặc bàn giao đã lưu.
     * Kiểm tra quyền kho và vai trò theo loại biên bản; sử dụng bản chụp tại thời điểm khóa hoặc
     * bàn giao.
     */
    public Object snapshot(long w, String type, long id) {
        g.warehouse(w);
        return switch (type) {
        case "count" -> {
            g.roles("ACT02", "ACT09");
            yield db.one(
                    "select m.* from count_minutes m join count_session s on s.id=m.session_id where s.warehouse_id=? and s.id=?",
                    w, id);
        }
        case "handover" -> {
            g.roles("ACT02", "ACT03");
            yield db.one(
                    "select h.* from handover h join shipment s on s.id=h.shipment_id where s.warehouse_id=? and s.id=?",
                    w, id);
        }
        default -> throw new IllegalArgumentException();
        };
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-18
     * Use case: UC-CNT-009, UC-OUT-007
     *
     * Xuất PDF từ bản chụp biên bản đã kiểm tra quyền truy cập.
     * Nội dung nghiệp vụ lấy từ biên bản đã lưu; thông tin mã và tên kho lấy từ danh mục hiện tại.
     */
    @SuppressWarnings("unchecked")
    public byte[] pdf(long w, String type, long id) {
        var data = (Map<String, Object>) snapshot(w, type, id);
        return MinutesPdf.render(type, id, data,
                db.one("select code,name from warehouse where id=?", w));
    }
}
