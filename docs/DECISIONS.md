# Quyết định triển khai

Các lựa chọn dưới đây là cấu hình/giả định của bản mã nguồn, chưa thay thế phê duyệt nghiệp vụ của nhóm.

| Điểm | Quyết định |
|---|---|
| Quy đổi đơn vị | Hỗ trợ theo phân tích 1.1. Hệ số theo SKU/UOM, dương, tối đa 6 chữ số lẻ. Dòng phiếu giữ entered_qty, conversion_factor và lượng cơ sở. Đổi cấu hình không đổi phiếu lịch sử. Kết quả quy đổi vượt 3 chữ số lẻ bị từ chối, không tự làm tròn. |
| IN-007 | Chỉ lập đề nghị và trình. Duyệt/áp dụng là lệnh riêng `/adjustments/{id}/decision`, không đổi phiếu gốc. Tuyến ACT09 là giả định tạm của bản triển khai; phải xác nhận chính thức. |
| Đảo nhập | Tạo IN_REVERSE cho lượng nhận còn lại sau các sửa đã áp dụng. Chặn đảo lần hai, kiểm không âm/khả dụng trong lúc áp dụng. |
| Từ chối xuất | RETURNED giữ nguyên phân bổ. Sửa và giữ lại phải kiểm đủ khả dụng; OUT-006 hủy mới giải phóng. Không tự hết hạn giữ. |
| Tuyến duyệt | ACT02 cho lượng trong ngưỡng; ACT09 khi vượt APPROVAL_THRESHOLD, người trình mang vai trò ACT02, hoặc sửa/đảo nhập. Ngưỡng hiện tính lượng cơ sở, không theo giá trị tiền. Không tự duyệt. Xuất luôn phải duyệt trong bản này. Chính sách điều kiện duyệt chưa tích hợp SYS. |
| Kiểm kê | Chụp T0 khi mở đợt. Khóa nghiệp vụ theo cặp vị trí/SKU từ bắt đầu đến ghi kết quả. Không tự hết hạn; ACT02 yêu cầu đếm lại có lý do để khép lượt treo. Chặn bắt đầu nếu có hàng đã soạn tại cặp. |
| Delta kiểm kê | book_at_count và cutoff_seq được chốt cùng lần ghi số đếm khi đang khóa. Delta = đếm − sổ tại mốc đếm. Cách này tương đương quy về T0: (đếm − biến động từ T0 tới mốc đếm) − sổ T0. Khi duyệt cộng delta lên tồn hiện tại, không gán bằng số đã đếm. |
| Đóng kiểm kê | Chỉ MATCHED/ADJUSTED được đóng. Đề nghị bị từ chối phải đếm lại hoặc lập đề nghị mới, không tự bỏ qua chênh lệch. Lượt đã điều chỉnh phải mở đợt mới nếu muốn kiểm lại. |
| Chặn xuất | Ghi blocked_qty lúc kiểm nhận, chốt cùng phiếu; sửa sai qua đề nghị sửa nhập. Thay tình trạng hàng độc lập sau nhập nằm ngoài bản này. |
| Nhập Excel | Chỉ thêm vị trí; mọi dòng cùng thành công hoặc rollback. Xem trước có hạn 30 phút. Kiểm lại ở commit, thứ tự AREA → AISLE → BIN. |
| Nguồn nhập | Cho nhiều đợt nhận cùng một chứng từ bằng source_occurrence_ref riêng. Quy tắc chống nhận vượt nguồn cần tích hợp module đơn hàng; bản này nhập căn cứ thủ công. |
| Mã và cấu trúc danh mục | Mã không đổi sau tạo; đơn vị cơ sở SKU không đổi. Nếu cần đổi cha/cấp vị trí, tạo vị trí mới. Sức chứa chỉ mô tả. |
| API | Dùng prefix catalog, các lệnh POST và version trong body. Đây là hợp đồng của mã nguồn, có khác tên đường dẫn/If-Match trong bản thiết kế. |
| Phân trang | Stock có offset/limit (tối đa 500). Các hàng đợi/phiếu giới hạn 200 bản ghi; UI tìm trong phần đã tải. Chưa hiện thực cursor/asOf ổn định trên nhiều lần tải như thiết kế đề xuất. |
| Báo cáo | Một truy vấn snapshot cho mỗi lần xem/xuất; chưa lưu ảnh chụp màn xem để xuất lại chính xác nếu có giao dịch mới giữa hai yêu cầu. |
| PDF | Xuất snapshot bất biến; font PDF mặc định dùng nội dung chuyển dấu Latin để tránh mất chữ. Cần bổ sung font Unicode được phép phân phối và bố cục biên bản đã duyệt trước sử dụng chính thức. |
| Phục hồi/lưu giữ | Chưa tự động hóa backup/restore, đối soát định kỳ, hạn lưu log/snapshot hay retry deadlock. Lỗi DB rollback giao dịch; người dùng tải lại và gửi lại. |
| Phạm vi loại trừ | FIFO/FEFO, lô/hạn dùng, điều chuyển đa kho, IoT, kế toán giá trị tồn, SSO/ủy quyền, quản trị tài khoản nền tảng và BI nâng cao. |

## Ranh giới dữ liệu

`CommandBus.execute` mở giao dịch, kiểm kho, nhận khóa chống lặp theo actor/action/UUID, rồi gọi nghiệp vụ. Chốt nghiệp vụ ghi hồ sơ, số dư, sổ, nhật ký và kết quả trả về cùng commit. POST tạo kho và tải chứng cứ là giao dịch riêng. Khóa tồn theo kho/SKU trước rồi SKU/vị trí; mọi thay đổi stock do StockService xử lý. Sổ không có API sửa/xóa.

Bản DB hiện dùng một tài khoản ứng dụng có quyền sở hữu migration. Việc phân tách migration user và runtime user, cùng quyền INSERT-only cho sổ/nhật ký, cần cấu hình trước vận hành thật; chưa coi là đã thực thi ở mức DB.
