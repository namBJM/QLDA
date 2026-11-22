package vn.ptit.slms.approval;

import static vn.ptit.slms.common.Db.*;

import java.math.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.ptit.slms.common.*;
import vn.ptit.slms.security.AccessGuard;

@Service
public class ApprovalService {

    private final Db db;
    private final AccessGuard g;
    private final Json json;
    private final BigDecimal threshold;

    public ApprovalService(Db d, AccessGuard g, Json j,
            @Value("${slms.approval-threshold}") BigDecimal t) {
        db = d;
        this.g = g;
        json = j;
        threshold = t;
    }

    private String column(String type) {
        return switch (type) {
        case "receipt" -> "receipt_id";
        case "shipment" -> "shipment_id";
        case "adjustment" -> "adjustment_id";
        default -> throw new IllegalArgumentException();
        };
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-06
     * Use case: UC-IN-003, UC-IN-007, UC-OUT-001, UC-CNT-005
     *
     * Tạo hồ sơ duyệt với bản chụp dữ liệu và phiên bản mục tiêu.
     * Chuyển ACT09 khi được yêu cầu, vượt ngưỡng số lượng hoặc người thao tác là ACT02; hồ sơ chờ
     * cũ được đánh dấu SUPERSEDED.
     */
    public void submit(long w, String type, long id, long version, String creator,
            BigDecimal amount, Object snapshot, boolean director) {
        String level = director || amount.abs().compareTo(threshold) > 0 || g.role().equals("ACT02")
                ? "ACT09"
                : "ACT02";
        db.run("update approval set status='SUPERSEDED' where " + column(type)
                + "=? and status='PENDING'", id);
        db.run("insert into approval(warehouse_id," + column(type)
                + ",policy_ref,level_code,target_version,submitted_by,payload,status) values (?,?,'M01-QUANTITY-V1',?,?,?,?::jsonb,'PENDING')",
                w, id, level, version, creator, json.write(snapshot));
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-07
     * Use case: UC-IN-004, UC-IN-007, UC-OUT-002, UC-CNT-006
     *
     * Ghi quyết định cho hồ sơ đang chờ duyệt.
     * Phải đúng cấp, đúng phiên bản và khác người trình; từ chối bắt buộc có lý do.
     */
    public Map<String, Object> decide(long w, String type, long id, long version, boolean accept,
            String reason) {
        var a = db.one("select * from approval where warehouse_id=? and " + column(type)
                + "=? and status='PENDING' for update", w, id);
        Problem.require(a.get("level_code").equals(g.role()), "APPROVAL_LEVEL",
                "Bạn không thuộc cấp duyệt của hồ sơ.");
        Problem.require(!g.actor().equals(a.get("submitted_by")), "SELF_APPROVAL",
                "Người lập không được tự duyệt.");
        Problem.require(lng(a.get("target_version")) == version, "STALE_APPROVAL",
                "Hồ sơ không còn đúng phiên bản đã trình.");
        if (!accept)
            Problem.require(!reason.isBlank(), "REASON", "Từ chối phải có lý do.");
        db.run("update approval set status=?,decided_by=?,decided_at=clock_timestamp(),reason=? where id=?",
                accept ? "APPROVED" : "REJECTED", g.actor(), reason, a.get("id"));
        return a;
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-08
     * Use case: UC-IN-006, UC-OUT-006, UC-CNT-007
     *
     * Vô hiệu hóa các hồ sơ duyệt còn chờ của chứng từ.
     * Chỉ chuyển hồ sơ PENDING sang SUPERSEDED; giữ lịch sử các quyết định đã ghi.
     */
    public void cancel(String type, long id) {
        db.run("update approval set status='SUPERSEDED' where " + column(type)
                + "=? and status='PENDING'", id);
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-08
     * Use case: UC-IN-004, UC-IN-007, UC-OUT-002, UC-CNT-006
     *
     * Tra cứu hồ sơ theo kho và cấp duyệt của người đang đăng nhập.
     * Kiểm tra quyền kho và trả tối đa 200 hồ sơ mới nhất, bao gồm lịch sử trạng thái.
     */
    public Object queue(long w) {
        g.warehouse(w);
        return db.rows(
                "select * from approval where warehouse_id=? and level_code=? order by id desc limit 200",
                w, g.role());
    }
}
