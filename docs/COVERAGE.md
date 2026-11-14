# Ánh xạ 32 use case

| Use case | Điểm hiện thực | Gói/lớp |
|---|---|---|
| UC-CAT-001 | warehouse / updateWarehouse | CatalogService / FileService |
| UC-CAT-002 | save locations | CatalogService / FileService |
| UC-CAT-003 | preview / commit | CatalogService / FileService |
| UC-CAT-004 | save skus / uoms / conversions | CatalogService / FileService |
| UC-IN-001 | create / update / submit | InboundService / AdjustmentService |
| UC-IN-002 | receive | InboundService / AdjustmentService |
| UC-IN-003 | save-discrepancy / discrepancy | InboundService / AdjustmentService |
| UC-IN-004 | decision | InboundService / AdjustmentService |
| UC-IN-005 | confirm | InboundService / AdjustmentService |
| UC-IN-006 | cancel | InboundService / AdjustmentService |
| UC-IN-007 | correction (decision riêng) | InboundService / AdjustmentService |
| UC-IN-008 | list / detail | InboundService / AdjustmentService |
| UC-STK-001 | query balances | StockService |
| UC-STK-002 | query ledger | StockService |
| UC-STK-003 | threshold | StockService |
| UC-STK-004 | query alerts / handle | StockService |
| UC-CNT-001 | create / open | CountService / AdjustmentService |
| UC-CNT-002 | assign | CountService / AdjustmentService |
| UC-CNT-003 | start / record | CountService / AdjustmentService |
| UC-CNT-004 | reconcile | CountService / AdjustmentService |
| UC-CNT-005 | request-adjustment | CountService / AdjustmentService |
| UC-CNT-006 | AdjustmentService.decide | CountService / AdjustmentService |
| UC-CNT-007 | recount / start / record | CountService / AdjustmentService |
| UC-CNT-008 | close | CountService / AdjustmentService |
| UC-CNT-009 | ReportService.snapshot / pdf | CountService / AdjustmentService |
| UC-OUT-001 | create / update / reserve | OutboundService / ReportService |
| UC-OUT-002 | decision | OutboundService / ReportService |
| UC-OUT-003 | pick | OutboundService / ReportService |
| UC-OUT-004 | issues / reallocate | OutboundService / ReportService |
| UC-OUT-005 | confirm | OutboundService / ReportService |
| UC-OUT-006 | cancel | OutboundService / ReportService |
| UC-OUT-007 | ReportService.snapshot / pdf | OutboundService / ReportService |

Đây là ánh xạ mã nguồn, không phải kết quả kiểm thử hoặc xác nhận đạt đầy đủ mọi nhánh trong tài liệu. Xem DECISIONS.md để biết các đơn giản hóa và điểm cần xác nhận.
