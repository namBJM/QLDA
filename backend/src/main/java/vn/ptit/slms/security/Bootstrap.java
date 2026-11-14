package vn.ptit.slms.security;
import vn.ptit.slms.common.*;import org.springframework.stereotype.Component;import org.springframework.boot.ApplicationRunner;import org.springframework.boot.ApplicationArguments;import org.springframework.beans.factory.annotation.Value;import org.springframework.security.crypto.password.PasswordEncoder;import org.springframework.transaction.annotation.Transactional;
@Component public class Bootstrap implements ApplicationRunner {
 private final Db db;private final PasswordEncoder encoder;private final String password;
 public Bootstrap(Db d,PasswordEncoder e,@Value("${slms.bootstrap-password}")String p){db=d;encoder=e;password=p;}
 @Transactional public void run(ApplicationArguments args){if(db.id("select count(*) from app_user")==0){Problem.require(password.length()>=12,"BOOTSTRAP_PASSWORD","Cần BOOTSTRAP_PASSWORD ít nhất 12 ký tự ở lần chạy đầu.");for(String[] u:new String[][]{{"manager","ACT02"},{"staff","ACT03"},{"director","ACT09"}})db.run("insert into app_user(username,password_hash,role) values (?,?,?)",u[0],encoder.encode(password),u[1]);}}
}
