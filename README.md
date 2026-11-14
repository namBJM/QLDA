# SLMS M01 — Quản lý kho và tồn kho

Mã nguồn ứng dụng React + TypeScript, Spring Boot 3.4.5 / Java 21 và PostgreSQL 17, triển khai từ hai tài liệu phân tích và thiết kế M01 phiên bản 1.1. Không chứa dữ liệu kinh doanh thật.

## Chạy nhanh bằng Docker

Cần Docker Engine / Docker Desktop có Compose và Internet để tải image, thư viện Maven, npm. Máy Windows dùng Linux containers.

1. Giải nén dự án và mở terminal trong thư mục chứa `docker-compose.yml`.
2. Sao chép `.env.example` thành `.env`. Đặt hai giá trị khác nhau cho `DB_PASSWORD` và `BOOTSTRAP_PASSWORD`; mật khẩu đăng nhập ít nhất 12 ký tự.
3. Chạy:

```powershell
docker compose up --build -d
docker compose logs -f backend
```

4. Mở **http://localhost:8088**. PostgreSQL chỉ nằm trong mạng Compose, không công khai cổng.

Tài khoản được khởi tạo khi bảng người dùng còn trống:

| Tài khoản | Vai trò | Mật khẩu lần đầu |
|---|---|---|
| manager | ACT02 — Quản lý kho | BOOTSTRAP_PASSWORD |
| staff | ACT03 — Nhân viên kho | BOOTSTRAP_PASSWORD |
| director | ACT09 — Lãnh đạo | BOOTSTRAP_PASSWORD |

Đây là ba tài khoản khởi tạo cho môi trường học tập. Kho mới được gán cho cả ba tài khoản để thực hiện đầy đủ luồng nhiều vai trò. Phân quyền vẫn được kiểm ở máy chủ bằng `user_warehouse`. Không có giao diện quản trị tài khoản/ủy quyền; tích hợp nền tảng tài khoản SLMS trước khi dùng thật. Không thay mật khẩu bootstrap trong `.env` để kỳ vọng ghi đè mật khẩu đã có trong DB.

## Thứ tự thao tác

1. Đăng nhập `manager`, tạo kho bằng nút **+** cạnh kho đang làm việc.
2. Trong **Danh mục**, tạo đơn vị tính → hàng hóa → khu AREA → dãy AISLE → ô BIN. Có thể nhập cây vị trí từ Excel sau khi xem trước không còn lỗi.
3. Nếu sử dụng đơn vị giao dịch khác đơn vị cơ sở, cấu hình **Quy đổi đơn vị** cho SKU đó.
4. Đăng nhập `staff`, tạo phiếu nhập, gửi kiểm nhận, chọn ô và ghi thực nhận theo đơn vị cơ sở. Nếu có lệch/hàng bị chặn, trình sai lệch; vào `manager` hoặc `director` đúng cấp để duyệt. Sau đó `staff` chốt nhập.
5. Tạo phiếu xuất → phân bổ đủ từng dòng theo vị trí → duyệt → ghi số lượng soạn cùng mã hàng/vị trí → bàn giao. Máy quét kiểu bàn phím có thể nhập vào trường mã. Các lượng giữ/soạn là đơn vị cơ sở.
6. `manager` tạo và mở đợt kiểm kê, phân công `staff`. Nhân viên bắt đầu, đếm, ghi kết quả và đối chiếu. Dòng lệch lập đề nghị; quản lý/lãnh đạo duyệt áp dụng. Đóng đợt khi mọi dòng khớp hoặc đã điều chỉnh, tải biên bản.
7. Theo dõi tồn, thẻ kho, ngưỡng, cảnh báo; xem/xuất báo cáo Excel, biên bản PDF.

Mỗi người dùng nên đăng nhập ở cửa sổ trình duyệt riêng khi cần đồng thời nhiều vai trò. Phiên của một origin chỉ có một tài khoản.

## Cấu trúc

```text
frontend/src/
  App.tsx                  Màn hình và luồng biểu mẫu tiếng Việt
  lib/api.ts               HTTP, CSRF, giữ khóa gửi khi mất phản hồi
  styles.css               Giao diện máy tính và điện thoại
backend/src/main/java/vn/ptit/slms/
  security/                Phiên, bcrypt, quyền vai trò/kho, bootstrap
  common/                  JDBC, JSON, lỗi và giao dịch chống gửi lặp
  catalog/                 Kho, vị trí, SKU, đơn vị, quy đổi
  stock/                   Khóa tồn, giữ, sổ biến động, cảnh báo
  inbound/                 Nhập kho
  outbound/                Xuất kho
  counting/                Kiểm kê
  approval/                Hồ sơ, tuyến và quyết định duyệt
  adjustment/              Đề nghị sửa/đảo nhập và điều chỉnh kiểm kê
  file/                    Excel, chứng cứ, báo cáo và PDF
  config/                  REST API và JSON số chính xác
backend/src/main/resources/db/migration/
  V1__schema.sql           25 bảng từ tài liệu thiết kế
  V2__implementation.sql   Tài khoản, quyền kho, quy đổi, mốc T0, chứng cứ
docs/
  DECISIONS.md             Các quyết định xử lý điểm lệch tài liệu
  API.md                   Hợp đồng API và ví dụ
  COVERAGE.md              Ánh xạ 32 use case sang mã nguồn
  BUILD_STATUS.md          Kết quả biên dịch và giới hạn xác minh
```

Lớp dịch vụ giữ trách nhiệm nghiệp vụ; `Db` gom truy vấn SQL tham số hóa. Controller và giao diện được giữ tập trung trong bản đầu để thuận tiện đọc mã; có thể tách thành controller theo phân hệ và React feature folders khi đội phát triển mở rộng.

## Chạy trực tiếp để phát triển

Cần JDK 21+, Maven 3.9+, Node 22 và PostgreSQL 17. Tạo DB `slms` và tài khoản `slms`, rồi đặt biến môi trường `DB_URL`, `DB_USER`, `DB_PASSWORD`, `BOOTSTRAP_PASSWORD` trong terminal chạy backend.

```powershell
cd backend
mvn -Dmaven.test.skip=true spring-boot:run
```

Ở terminal khác:

```powershell
cd frontend
npm install
npm run dev
```

Mở URL do Vite in ra, mặc định http://localhost:5173. Vite chuyển `/api` tới backend 8080. OpenAPI ở http://localhost:8080/v3/api-docs và Swagger UI ở http://localhost:8080/swagger-ui/index.html (cần phiên đăng nhập trên cùng origin; dùng bản Compose tại đường dẫn tương ứng thuận tiện hơn).

Build mã nguồn, không chạy kiểm thử:

```powershell
cd backend
mvn -Dmaven.test.skip=true package
```

```powershell
cd frontend
npm run build
```

## Dữ liệu và vận hành

Volume `postgres-data` lưu DB, `evidence-data` lưu chứng cứ. `docker compose down` giữ các volume. Không dùng tùy chọn xóa volume nếu muốn giữ dữ liệu. Sao lưu cả DB và volume chứng cứ theo cùng thời điểm nghiệp vụ. Cấu hình Compose chỉ mở ứng dụng trên loopback của máy để phát triển. Khi đưa lên máy chủ, cần TLS, cookie Secure, tài khoản riêng, quy trình backup/restore và chính sách cấp quyền chính thức.

Không nạp trực tiếp `stock_balance`: tạo phiếu nhập có nguồn `OPENING` cho dữ liệu đầu kỳ để đồng thời ghi sổ.

Không có bộ kiểm thử theo yêu cầu. Xem **docs/BUILD_STATUS.md** trước khi đánh giá mức độ sẵn sàng của bản này.
