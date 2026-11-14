package vn.ptit.slms.security;
import vn.ptit.slms.common.*;import org.springframework.stereotype.Component;import org.springframework.security.core.context.SecurityContextHolder;import java.util.*;
@Component public class AccessGuard {
 private final Db db;public AccessGuard(Db db){this.db=db;}
 public String actor(){return SecurityContextHolder.getContext().getAuthentication().getName();}
 public String role(){return db.one("select role from app_user where username=? and active",actor()).get("role").toString();}
 public void roles(String... allowed){if(!Arrays.asList(allowed).contains(role()))throw new Problem(403,"SCOPE_DENIED","Vai trò không được phép thực hiện.");}
 public void warehouse(long w){if(db.id("select count(*) from user_warehouse where username=? and warehouse_id=?",actor(),w)==0)throw new Problem(403,"SCOPE_DENIED","Bạn chưa được cấp quyền kho này.");}
 public Map<String,Object> me(){return Map.of("username",actor(),"role",role(),"warehouses",db.rows("select w.* from warehouse w join user_warehouse u on u.warehouse_id=w.id where u.username=? order by w.id",actor()));}
}
