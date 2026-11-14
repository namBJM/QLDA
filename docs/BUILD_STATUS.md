# Trạng thái xác minh — 14/11/2026

Theo yêu cầu, không tạo và không chạy bộ kiểm thử unit, tích hợp, nghiệp vụ, UI hoặc tải.

| Hạng mục | Kết quả |
|---|---|
| TypeScript | `tsc --noEmit` đã chạy thành công, không báo lỗi. |
| Frontend bundle | Vite đã tạo bundle production thành công. Do môi trường hạn chế quyền duyệt thư mục cha, gọi Vite qua JS API với configFile:false, cấu hình JSX automatic, thay cho nạp vite.config.ts. |
| Java | Đã phân tích cú pháp bằng JavaCompiler/JavacTask trên 21 tệp Java. Đây không phải biên dịch đầy đủ hoặc kiểm chứng type resolution với Spring. |
| Maven | Tìm thấy Maven trong NetBeans, nhưng không có parent Spring Boot 3.4.5 trong cache; tải Internet bị chặn. Chưa build được backend JAR. |
| PostgreSQL/Docker | Không có Docker CLI/PostgreSQL server sẵn để chạy. Chưa áp dụng migration hoặc chạy hệ thống end-to-end. |
| Phát hành | Giao mã nguồn + hướng dẫn Docker. Không có URL production hoặc backend đang chạy. |

Thư viện npm được lấy từ cache có sẵn để kiểm tra frontend; không đóng gói node_modules vào bản giao. Người nhận cần Internet ở lần `npm install`/Docker build đầu. Maven và Docker cũng cần kết nối tải dependency/image.

Vì backend chưa biên dịch và chưa chạy với DB thật, đây là bản triển khai mã nguồn đầu tiên, chưa được xác nhận sẵn sàng vận hành. Các giới hạn nghiệp vụ/đầu ra được liệt kê minh bạch trong DECISIONS.md.
