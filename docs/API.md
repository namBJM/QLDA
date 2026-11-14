# Hợp đồng API triển khai

Gốc: `/api/v1/m01/warehouses/{warehouseId}`. JSON UTF-8. ID bigint và BigDecimal xuất dưới dạng chuỗi để tránh mất chính xác. Các ID nhập cho phép chuỗi số. `version` trong body phải bằng phiên bản vừa đọc. Mỗi lệnh POST dưới kho (trừ preview/tải tệp) cần `Idempotency-Key: UUID` và CSRF header từ `/api/auth/csrf`. Giữ nguyên khóa nếu gửi lại do mất phản hồi.

## Phiên

GET `/api/auth/csrf` → `{token,headerName}`. POST `/api/auth/login` dùng form URL encoded `username/password` và CSRF. Đăng nhập thành công, lấy token CSRF mới. GET `/api/auth/me` trả vai trò, kho; POST `/api/auth/logout` kết thúc phiên. Cookie HttpOnly, SameSite Lax.

## Danh mục

- GET `/api/v1/m01/warehouses`; POST cùng URL tạo `{code,name,description}`.
- POST gốc dưới kho sửa `{code,name,description,active,version}`.
- GET/POST `/catalog/locations`: `{code,name,kind,parentId}`; cập nhật thêm `id,version,active`.
- GET/POST `/catalog/uoms`: `{code,name,symbol}`; sửa thêm `id,version,active`.
- GET/POST `/catalog/skus`: `{code,name,barcode,uomId}`; sửa thêm `id,version,active`.
- GET/POST `/catalog/conversions`: `{skuId,uomId,factor}`.
- GET `/catalog/users`: danh sách người thuộc kho, không trả hash mật khẩu.

## Nhập / xuất

GET `/receipts` hoặc `/shipments`: tối đa 200 bản ghi. GET `/{type}/{id}`: hồ sơ kèm dòng, phiên bản; xuất còn có reservations.

POST `/receipts` hoặc `/shipments`:

```json
{"sourceType":"MANUAL","sourceRef":"CT-001","lines":[{"skuId":"1","uomId":"1","qty":"10"}]}
```

POST `/receipts/{id}/{action}`:

| action | Body ngoài version |
|---|---|
| update | sourceType, sourceRef, lines[{lineNo,qty}] — sửa lượng theo đơn vị giao dịch, giữ SKU/UOM của dòng |
| submit | Không thêm trường |
| receive | lines[{lineNo,locationId,receivedQty,blockedQty,conditionNote?}] — lượng cơ sở |
| save-discrepancy | reason?, evidenceIds? — lưu giải trình tạm |
| discrepancy | reason, evidenceIds? — trình duyệt |
| decision | accept:boolean, reason nếu từ chối |
| confirm | Không thêm trường |
| cancel | reason |
| corrections | reason, lines[{lineNo,deltaBook,deltaBlocked}], evidenceIds?; hoặc reverse:true để đề nghị đảo toàn bộ phần nhập còn lại |

POST `/shipments/{id}/{action}`:

| action | Body ngoài version |
|---|---|
| update | sourceType,sourceRef,lines[{lineNo,qty}] |
| reserve / reallocate | allocations[{lineNo,locationId,qty}], returnedPicked nếu có hàng đã soạn cần hoàn trả |
| decision | accept, reason khi từ chối |
| pick | picks[{reservationId,qty,scanCode,locationCode}] — số tổng đã soạn, không phải số cộng thêm |
| issues | reason, evidenceIds? |
| confirm | receiverName, sourceRef? |
| cancel | reason, returnedPicked nếu có |

## Tồn và kiểm kê

- GET `/stock/balances`, `/stock/ledger`, `/stock/alerts` có offset/limit.
- POST `/thresholds`: `{skuId,minQty}`.
- POST `/alerts/{id}/handle`: `{version,resolution}`.
- GET/POST `/count-sessions`; tạo `{items:[{locationId,skuId}]}`.
- GET `/count-sessions/{id}` trả items kèm lượt hiện hành.
- POST `/count-sessions/{id}/open`: `{version}`; `/close`: `{version,note?}`.
- POST `/count-items/{id}/assign`: `{version,username}`.
- POST `/count-items/{id}/start`: `{version}`.
- POST `/count-items/{id}/record`: `{version,qty,conditionNote?}`.
- POST `/count-items/{id}/reconcile`: `{version}`.
- POST `/count-items/{id}/request-adjustment`: `{version,reason,evidenceIds?}`.
- POST `/count-items/{id}/recount`: `{version,reason}`.
- GET `/adjustments`; POST `/adjustments/{id}/decision`: `{version,accept,reason?}`.
- GET `/approval-tasks`: hàng đợi của cấp duyệt hiện hành, payload là snapshot.

## Tệp và báo cáo

- GET `/location-imports/template`: mẫu XLSX.
- POST `/location-imports/preview`: multipart `file`; trả id, rows, errors, canCommit.
- POST `/location-imports/{uuid}/commit`: chỉ commit khi preview hợp lệ, đúng người, chưa hết hạn.
- POST `/evidence`: multipart `file` PDF/PNG/JPEG tối đa 5 MB; GET `/evidence/{uuid}` tải có kiểm quyền kho.
- GET `/reports?from=ISO_OFFSET&to=ISO_OFFSET`: kỳ [from,to); `/reports/export` trả XLSX.
- GET `/minutes/count/{sessionId}` hoặc `/minutes/handover/{shipmentId}`: snapshot; thêm `/pdf` để tải PDF.

Lỗi nghiệp vụ `{code,message,requestId}`; 401 chưa đăng nhập, 403 thiếu quyền, 404 không có dữ liệu trong kho, 409 trạng thái/phiên bản/ràng buộc, 422 dữ liệu sai. Lỗi Spring Security có thể không dùng cùng DTO. Lệnh chỉ trả kết quả thành công sau commit. OpenAPI tự sinh có ở `/v3/api-docs`.
