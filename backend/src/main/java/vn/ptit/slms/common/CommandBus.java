package vn.ptit.slms.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ptit.slms.security.AccessGuard;

@Service
public class CommandBus {

    private final Db db;
    private final Json json;
    private final AccessGuard guard;

    public CommandBus(Db d, Json j, AccessGuard g) {
        db = d;
        json = j;
        guard = g;
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-06
     * Use case: Dùng chung cho các lệnh UC-CAT, UC-IN, UC-STK, UC-CNT, UC-OUT
     *
     * Thực thi lệnh có kiểm tra quyền kho, chống gửi lặp và ghi nhật ký.
     * Cùng người, kho, thao tác và khóa phải có cùng payload; gửi lại trả kết quả đã lưu. Lệnh,
     * nhật ký và kết quả chống lặp cùng commit hoặc rollback.
     */
    @Transactional
    public Object execute(long w, String action, UUID key, Object payload, Supplier<Object> work) {
        guard.warehouse(w);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.write(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        db.run("insert into command_dedup(actor_id,action,request_key,request_hash) values (?,?,?,?) on conflict do nothing",
                guard.actor(), w + ":" + action, key, hash);
        var prior = db.one(
                "select * from command_dedup where actor_id=? and action=? and request_key=? for update",
                guard.actor(), w + ":" + action, key);
        Problem.require(hash.equals(prior.get("request_hash")), "IDEMPOTENCY_CONFLICT",
                "Khóa gửi lặp đã được dùng cho dữ liệu khác.");
        if (prior.get("response_body") != null)
            return prior.get("response_body");
        if (payload instanceof Map<?, ?> input && input.get("evidenceIds") instanceof List<?> ids) {
            for (Object evidence : ids)
                db.one("select id from evidence where id=? and warehouse_id=?",
                        UUID.fromString(evidence.toString()), w);
        }
        Object result = work.get();
        db.run("insert into audit_event(warehouse_id,actor_id,action,entity_type,entity_id,request_id,after_state) values (?,?,?,'COMMAND',?,?,?::jsonb)",
                w, guard.actor(), action, action, key, json.write(result));
        db.run("update command_dedup set response_status=200,response_body=?::jsonb where actor_id=? and action=? and request_key=?",
                json.write(result), guard.actor(), w + ":" + action, key);
        return result;
    }
}
