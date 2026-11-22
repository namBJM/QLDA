package vn.ptit.slms.file;

import static vn.ptit.slms.common.Db.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.ptit.slms.catalog.*;
import vn.ptit.slms.common.*;
import vn.ptit.slms.security.*;

@Service
public class FileService {

    private final Db db;
    private final AccessGuard g;
    private final Json json;
    private final CatalogService catalog;
    private final Path dir;

    public FileService(Db d, AccessGuard g, Json j, CatalogService c,
            @Value("${slms.evidence-dir}") String path) {
        db = d;
        this.g = g;
        json = j;
        catalog = c;
        dir = Path.of(path).toAbsolutePath().normalize();
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-11
     * Use case: UC-CAT-003
     *
     * Đọc và kiểm tra tệp Excel vị trí trước khi nhập chính thức.
     * Chỉ ACT02 thuộc kho được xem trước; tối đa 5.000 dòng, kiểm tra mã và cấp cha. Bản xem trước
     * hợp lệ có hạn 30 phút.
     */
    @Transactional
    public Object preview(long w, MultipartFile file) {
        g.warehouse(w);
        g.roles("ACT02");
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        try (var book = new XSSFWorkbook(file.getInputStream())) {
            var sheet = book.getSheetAt(0);
            Problem.require(sheet.getLastRowNum() <= 5000, "FILE_LIMIT", "Tối đa 5.000 dòng.");
            var fmt = new DataFormatter();
            for (int n = 1; n <= sheet.getLastRowNum(); n++) {
                var row = sheet.getRow(n);
                if (row == null)
                    continue;
                String code = fmt.formatCellValue(row.getCell(0)).trim(),
                        name = fmt.formatCellValue(row.getCell(1)).trim(),
                        kind = fmt.formatCellValue(row.getCell(2)).trim(),
                        parent = fmt.formatCellValue(row.getCell(3)).trim();
                if (code.isBlank() && name.isBlank())
                    continue;
                boolean ok = !code.isBlank() && !name.isBlank()
                        && Set.of("AREA", "AISLE", "BIN").contains(kind) && codes.add(code)
                        && db.id("select count(*) from location where warehouse_id=? and code=?", w,
                                code) == 0;
                if (!ok)
                    errors.add(Map.of("row", n + 1, "message",
                            "Mã trùng/thiếu hoặc loại vị trí không hợp lệ."));
                rows.add(Map.of("code", code, "name", name, "kind", kind, "parentCode", parent));
            }
        } catch (Problem e) {
            throw e;
        } catch (Exception e) {
            throw new Problem(422, "XLSX_INVALID", "Không đọc được tệp .xlsx.");
        }
        for (int n = 0; n < rows.size(); n++) {
            var r = rows.get(n);
            String kind = r.get("kind").toString(), parent = r.get("parentCode").toString();
            if (kind.equals("AREA")) {
                if (!parent.isEmpty())
                    errors.add(Map.of("row", n + 2, "message", "AREA không có cha."));
            } else {
                String expect = kind.equals("AISLE") ? "AREA" : "AISLE";
                boolean exists = db.id(
                        "select count(*) from location where warehouse_id=? and code=? and kind=? and active",
                        w, parent, expect) > 0
                        || rows.stream().anyMatch(
                                x -> x.get("code").equals(parent) && x.get("kind").equals(expect));
                if (!exists)
                    errors.add(Map.of("row", n + 2, "message", "Cha không tồn tại hoặc sai cấp."));
            }
        }
        UUID id = UUID.randomUUID();
        if (errors.isEmpty() && !rows.isEmpty())
            db.run("insert into import_preview(id,warehouse_id,uploaded_by,file_hash,rows_json,expires_at) values (?,?,?, ?,?::jsonb,clock_timestamp()+interval '30 minutes')",
                    id, w, g.actor(), Integer.toHexString(json.write(rows).hashCode()),
                    json.write(rows));
        return Map.of("id", id, "rows", rows, "errors", errors, "canCommit",
                errors.isEmpty() && !rows.isEmpty());
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-12
     * Use case: UC-CAT-003
     *
     * Áp dụng bản xem trước vị trí theo thứ tự AREA, AISLE rồi BIN.
     * Chỉ ACT02 và đúng người tải được áp dụng bản còn hạn; bản đã áp dụng trả lại kết quả, không
     * tạo thêm vị trí. Bên gọi mở giao dịch qua CommandBus.
     */
    public Object commit(long w, UUID id) {
        g.roles("ACT02");
        db.one("select * from warehouse where id=? for update", w);
        var preview = db.one(
                "select * from import_preview where warehouse_id=? and id=? and uploaded_by=? and expires_at>clock_timestamp() for update",
                w, id, g.actor());
        if (preview.get("applied_at") != null)
            return Map.of("id", id, "applied", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) preview.get("rows_json");
        for (String kind : List.of("AREA", "AISLE", "BIN"))
            for (var row : rows)
                if (row.get("kind").equals(kind)) {
                    var input = new LinkedHashMap<>(row);
                    String parent = text(row, "parentCode");
                    if (!parent.isBlank())
                        input.put("parentId",
                                db.one("select id from location where warehouse_id=? and code=?", w,
                                        parent).get("id"));
                    catalog.save(w, "locations", input);
                }
        db.run("update import_preview set applied_at=clock_timestamp(),result_json='{}'::jsonb where id=?",
                id);
        return Map.of("id", id, "applied", true);
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-11
     * Use case: UC-CAT-003
     *
     * Tạo tệp Excel mẫu với bốn cột và ví dụ cây vị trí.
     * Giữ tên cột code, name, kind, parentCode tương ứng với thứ tự đọc của preview.
     */
    public byte[] template() {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var s = book.createSheet("ViTri");
            var h = s.createRow(0);
            String[] names = { "code", "name", "kind", "parentCode" };
            for (int i = 0; i < 4; i++) {
                h.createCell(i).setCellValue(names[i]);
                s.setColumnWidth(i, 6500);
            }
            String[][] example = { { "A", "Khu A", "AREA", "" }, { "A-01", "Dãy 01", "AISLE", "A" },
                    { "A-01-01", "Ô 01", "BIN", "A-01" }, };
            for (int n = 0; n < 3; n++) {
                var r = s.createRow(n + 1);
                for (int i = 0; i < 4; i++)
                    r.createCell(i).setCellValue(example[n][i]);
            }
            book.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-13
     * Use case: UC-IN-003, UC-IN-007, UC-OUT-004, UC-CNT-005
     *
     * Lưu tệp chứng cứ và thông tin sở hữu trong kho.
     * Kiểm tra quyền kho; chỉ nhận tệp không rỗng, tối đa 5 MB và MIME PDF, PNG hoặc JPEG. Tệp vật
     * lý dùng UUID làm tên.
     */
    @Transactional
    public Object upload(long w, MultipartFile f) {
        g.warehouse(w);
        Problem.require(!f.isEmpty() && f.getSize() <= 5242880, "FILE_LIMIT", "Tệp tối đa 5 MB.");
        String type = Objects.toString(f.getContentType(), "application/octet-stream");
        Problem.require(Set.of("application/pdf", "image/png", "image/jpeg").contains(type),
                "FILE_TYPE", "Chỉ nhận PDF, PNG, JPEG.");
        UUID id = UUID.randomUUID();
        try {
            Files.createDirectories(dir);
            Files.copy(f.getInputStream(), dir.resolve(id.toString()));
            db.run("insert into evidence(id,warehouse_id,uploaded_by,original_name,content_type,size_bytes) values (?,?,?,?,?,?)",
                    id, w, g.actor(), Objects.toString(f.getOriginalFilename(), "evidence"), type,
                    f.getSize());
            return Map.of("id", id);
        } catch (IOException e) {
            throw new Problem(503, "FILE_IO", "Không lưu được tệp.");
        }
    }

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-13
     * Use case: UC-IN-003, UC-IN-007, UC-OUT-004, UC-CNT-005
     *
     * Tải chứng cứ đã lưu theo mã UUID.
     * Chỉ đọc khi chứng cứ thuộc kho được cấp quyền; trả lỗi khi tệp vật lý không còn tồn tại.
     */
    public byte[] download(long w, UUID id) {
        g.warehouse(w);
        db.one("select * from evidence where warehouse_id=? and id=?", w, id);
        try {
            return Files.readAllBytes(dir.resolve(id.toString()));
        } catch (IOException e) {
            throw new Problem(404, "FILE_MISSING", "Tệp không tồn tại.");
        }
    }
}
