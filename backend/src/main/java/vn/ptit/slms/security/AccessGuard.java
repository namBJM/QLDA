package vn.ptit.slms.security;

import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import vn.ptit.slms.common.*;

@Component
public class AccessGuard {

    private final Db db;

    public AccessGuard(Db db) {
        this.db = db;
    }

    public String actor() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    public String role() {
        return db.one("select role from app_user where username=? and active", actor()).get("role")
                .toString();
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-03
     * Use case: Dùng chung cho các thao tác có phân quyền trong M01
     *
     * Kiểm tra vai trò của tài khoản đang hoạt động trước khi xử lý nghiệp vụ.
     * Chỉ cho phép vai trò có trong danh sách; thiếu quyền trả lỗi 403.
     */
    public void roles(String... allowed) {
        if (!Arrays.asList(allowed).contains(role()))
            throw new Problem(403, "SCOPE_DENIED", "Vai trò không được phép thực hiện.");
    }

    /**
     * Người viết: Đào Khánh Ninh
     * Ngày viết: 2026-11-04
     * Use case: Dùng chung cho các nghiệp vụ theo kho trong M01
     *
     * Kiểm tra người đăng nhập đã được cấp quyền vào kho yêu cầu.
     * Quyền lấy từ user_warehouse ở máy chủ; không dựa vào kho được chọn trên giao diện.
     */
    public void warehouse(long w) {
        if (db.id("select count(*) from user_warehouse where username=? and warehouse_id=?",
                actor(), w) == 0)
            throw new Problem(403, "SCOPE_DENIED", "Bạn chưa được cấp quyền kho này.");
    }

    public Map<String, Object> me() {
        return Map.of("username", actor(), "role", role(), "warehouses", db.rows(
                "select w.* from warehouse w join user_warehouse u on u.warehouse_id=w.id where u.username=? order by w.id",
                actor()));
    }
}
