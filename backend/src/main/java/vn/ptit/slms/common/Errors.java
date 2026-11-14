package vn.ptit.slms.common;
import org.springframework.web.bind.annotation.*;import org.springframework.http.ResponseEntity;import org.springframework.dao.DataIntegrityViolationException;import java.util.*;
@RestControllerAdvice public class Errors {
 @ExceptionHandler(Problem.class) ResponseEntity<?> problem(Problem e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage(),"requestId",UUID.randomUUID().toString()));}
 @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MethodArgumentNotValidException.class}) ResponseEntity<?> invalid(Exception e){return ResponseEntity.unprocessableEntity().body(Map.of("code","VALIDATION","message","Dữ liệu không hợp lệ. Kiểm tra các trường bắt buộc và số lượng."));}
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict(Exception e){return ResponseEntity.status(409).body(Map.of("code","DATA_CONFLICT","message","Mã đã tồn tại hoặc dữ liệu vi phạm ràng buộc."));}
}
