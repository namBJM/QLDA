package vn.ptit.slms.config;

import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.ptit.slms.adjustment.*;
import vn.ptit.slms.approval.*;
import vn.ptit.slms.catalog.*;
import vn.ptit.slms.common.*;
import vn.ptit.slms.counting.*;
import vn.ptit.slms.file.*;
import vn.ptit.slms.inbound.*;
import vn.ptit.slms.outbound.*;
import vn.ptit.slms.security.*;
import vn.ptit.slms.stock.*;

/**
 * Tiếp nhận REST request và chuyển xử lý nghiệp vụ sang các service.
 * Các lệnh ghi theo kho đi qua CommandBus để kiểm tra quyền, quản lý giao dịch và chống gửi lặp.
 */
@RestController
@RequestMapping("/api/v1/m01")
public class ApiController {

    private final CommandBus bus;
    private final CatalogService cat;
    private final InboundService in;
    private final OutboundService out;
    private final CountService count;
    private final StockService stock;
    private final AdjustmentService adj;
    private final ApprovalService approval;
    private final FileService files;
    private final ReportService reports;
    private final AccessGuard guard;

    public ApiController(CommandBus b, CatalogService c, InboundService i, OutboundService o,
            CountService n, StockService s, AdjustmentService a, ApprovalService ap, FileService f,
            ReportService r, AccessGuard g) {
        bus = b;
        cat = c;
        in = i;
        out = o;
        count = n;
        stock = s;
        adj = a;
        approval = ap;
        files = f;
        reports = r;
        guard = g;
    }

    @GetMapping("/warehouses")
    Object warehouses() {
        return guard.me().get("warehouses");
    }

    @PostMapping("/warehouses")
    Object warehouse(@RequestBody Map<String, Object> p) {
        return cat.warehouse(p);
    }

    @PostMapping("/warehouses/{w}")
    Object updateWarehouse(@PathVariable long w, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody Map<String, Object> p) {
        return bus.execute(w, "warehouse/update", key, p, () -> cat.updateWarehouse(w, p));
    }

    @GetMapping("/warehouses/{w}/catalog/{kind}")
    Object catalog(@PathVariable long w, @PathVariable String kind) {
        return cat.list(w, kind);
    }

    @PostMapping("/warehouses/{w}/catalog/{kind}")
    Object save(@PathVariable long w, @PathVariable String kind,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Map<String, Object> p) {
        return bus.execute(w, "catalog/" + kind, key, p, () -> cat.save(w, kind, p));
    }

    @GetMapping("/warehouses/{w}/receipts")
    Object receipts(@PathVariable long w) {
        return in.list(w);
    }

    @GetMapping("/warehouses/{w}/receipts/{id}")
    Object receipt(@PathVariable long w, @PathVariable long id) {
        return in.detail(w, id);
    }

    @PostMapping("/warehouses/{w}/receipts")
    Object createReceipt(@PathVariable long w, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody Map<String, Object> p) {
        return bus.execute(w, "receipt/create", key, p, () -> in.create(w, p));
    }

    @PostMapping("/warehouses/{w}/receipts/{id}/{action}")
    Object receiptAction(@PathVariable long w, @PathVariable long id, @PathVariable String action,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Map<String, Object> p) {
        return bus.execute(w, "receipt/" + id + "/" + action, key, p,
                () -> action.equals("corrections") ? adj.correction(w, id, p)
                        : in.action(w, id, action, p));
    }

    @GetMapping("/warehouses/{w}/shipments")
    Object shipments(@PathVariable long w) {
        return out.list(w);
    }

    @GetMapping("/warehouses/{w}/shipments/{id}")
    Object shipment(@PathVariable long w, @PathVariable long id) {
        return out.detail(w, id);
    }

    @PostMapping("/warehouses/{w}/shipments")
    Object createShipment(@PathVariable long w, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody Map<String, Object> p) {
        return bus.execute(w, "shipment/create", key, p, () -> out.create(w, p));
    }

    @PostMapping("/warehouses/{w}/shipments/{id}/{action}")
    Object shipmentAction(@PathVariable long w, @PathVariable long id, @PathVariable String action,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Map<String, Object> p) {
        return bus.execute(w, "shipment/" + id + "/" + action, key, p,
                () -> out.action(w, id, action, p));
    }

    @GetMapping("/warehouses/{w}/stock/{kind}")
    Object stock(@PathVariable long w, @PathVariable String kind,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "200") int limit) {
        return stock.query(w, kind, Math.max(0, offset), Math.max(1, Math.min(500, limit)));
    }

    @PostMapping("/warehouses/{w}/thresholds")
    Object threshold(@PathVariable long w, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody Map<String, Object> p) {
        return bus.execute(w, "threshold", key, p, () -> stock.threshold(w, p));
    }

    @PostMapping("/warehouses/{w}/alerts/{id}/handle")
    Object handle(@PathVariable long w, @PathVariable long id,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Map<String, Object> p) {
        return bus.execute(w, "alert/" + id, key, p, () -> stock.handle(w, id, p));
    }

    @GetMapping("/warehouses/{w}/count-sessions")
    Object sessions(@PathVariable long w) {
        return count.list(w);
    }

    @GetMapping("/warehouses/{w}/count-sessions/{id}")
    Object session(@PathVariable long w, @PathVariable long id) {
        return count.detail(w, id);
    }

    @PostMapping("/warehouses/{w}/count-sessions")
    Object createSession(@PathVariable long w, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody Map<String, Object> p) {
        return bus.execute(w, "count/create", key, p, () -> count.create(w, p));
    }

    @PostMapping("/warehouses/{w}/count-sessions/{id}/{action}")
    Object sessionAction(@PathVariable long w, @PathVariable long id, @PathVariable String action,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Map<String, Object> p) {
        return bus.execute(w, "count/" + id + "/" + action, key, p,
                () -> count.sessionAction(w, id, action, p));
    }

    @PostMapping("/warehouses/{w}/count-items/{id}/{action}")
    Object itemAction(@PathVariable long w, @PathVariable long id, @PathVariable String action,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Map<String, Object> p) {
        return bus.execute(w, "item/" + id + "/" + action, key, p,
                () -> count.itemAction(w, id, action, p));
    }

    @GetMapping("/warehouses/{w}/adjustments")
    Object adjustments(@PathVariable long w) {
        return adj.list(w);
    }

    @PostMapping("/warehouses/{w}/adjustments/{id}/decision")
    Object decide(@PathVariable long w, @PathVariable long id,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Map<String, Object> p) {
        return bus.execute(w, "adjustment/" + id + "/decision", key, p, () -> adj.decide(w, id, p));
    }

    @GetMapping("/warehouses/{w}/approval-tasks")
    Object tasks(@PathVariable long w) {
        return approval.queue(w);
    }

    @GetMapping("/warehouses/{w}/location-imports/template")
    ResponseEntity<byte[]> template(@PathVariable long w) {
        guard.warehouse(w);
        guard.roles("ACT02");
        return download("mau_vi_tri.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                files.template());
    }

    @PostMapping("/warehouses/{w}/location-imports/preview")
    Object preview(@PathVariable long w, @RequestParam MultipartFile file) {
        return files.preview(w, file);
    }

    @PostMapping("/warehouses/{w}/location-imports/{id}/commit")
    Object commit(@PathVariable long w, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key) {
        return bus.execute(w, "import/" + id, key, Map.of("id", id), () -> files.commit(w, id));
    }

    @PostMapping("/warehouses/{w}/evidence")
    Object evidence(@PathVariable long w, @RequestParam MultipartFile file) {
        return files.upload(w, file);
    }

    @GetMapping("/warehouses/{w}/evidence/{id}")
    ResponseEntity<byte[]> evidence(@PathVariable long w, @PathVariable UUID id) {
        return download(id.toString(), "application/octet-stream", files.download(w, id));
    }

    @GetMapping("/warehouses/{w}/reports")
    Object report(@PathVariable long w, @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to) {
        return reports.report(w, from, to);
    }

    @GetMapping("/warehouses/{w}/reports/export")
    ResponseEntity<byte[]> export(@PathVariable long w, @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to) {
        return download("nhap_xuat_ton.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                reports.excel(reports.report(w, from, to)));
    }

    @GetMapping("/warehouses/{w}/minutes/{type}/{id}")
    Object minutes(@PathVariable long w, @PathVariable String type, @PathVariable long id) {
        return reports.snapshot(w, type, id);
    }

    @GetMapping("/warehouses/{w}/minutes/{type}/{id}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable long w, @PathVariable String type,
            @PathVariable long id) {
        return download(type + "-" + id + ".pdf", "application/pdf", reports.pdf(w, type, id));
    }

    private ResponseEntity<byte[]> download(String name, String type, byte[] data) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .header("X-Content-Type-Options", "nosniff").body(data);
    }
}
