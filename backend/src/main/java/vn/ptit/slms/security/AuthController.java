package vn.ptit.slms.security;
import org.springframework.web.bind.annotation.*;import org.springframework.security.web.csrf.CsrfToken;
@RestController @RequestMapping("/api/auth") public class AuthController {private final AccessGuard guard;public AuthController(AccessGuard g){guard=g;}
 @GetMapping("/csrf") Object csrf(CsrfToken token){return java.util.Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
 @GetMapping("/me") Object me(){return guard.me();}
}
