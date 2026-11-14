package vn.ptit.slms.common;
import org.springframework.stereotype.Repository;import org.springframework.jdbc.core.JdbcTemplate;import java.util.*;import java.math.BigDecimal;
@Repository public class Db {
 private final JdbcTemplate sql;private final Json json;public Db(JdbcTemplate sql,Json json){this.sql=sql;this.json=json;}
 public List<Map<String,Object>> rows(String q,Object... a){var result=sql.queryForList(q,a);for(var row:result)for(var e:row.entrySet()){Object value=e.getValue();if(value!=null&&value.getClass().getName().equals("org.postgresql.util.PGobject")){String raw=value.toString();if(raw.startsWith("{"))e.setValue(json.read(raw));else if(raw.startsWith("["))e.setValue(json.read("{\"items\":"+raw+"}").get("items"));}}return result;}
 public Map<String,Object> one(String q,Object... a){var r=rows(q,a);if(r.isEmpty())throw new Problem(404,"NOT_FOUND","Không tìm thấy dữ liệu trong phạm vi kho.");return r.get(0);}
 public long id(String q,Object... a){return sql.queryForObject(q,Long.class,a);}
 public BigDecimal decimal(String q,Object... a){return sql.queryForObject(q,BigDecimal.class,a);}
 public int run(String q,Object... a){return sql.update(q,a);}
 public static long lng(Object x){return Long.parseLong(x.toString());}
 public static BigDecimal dec(Object x){return new BigDecimal(x.toString());}
 public static String text(Map<String,Object> m,String k){var v=m.get(k);return v==null?"":v.toString().trim();}
 public static String required(Map<String,Object> m,String k){var v=text(m,k);Problem.require(!v.isBlank(),"REQUIRED","Thiếu trường "+k);return v;}
 public static BigDecimal qty(Object x){BigDecimal v=dec(x);Problem.require(v.signum()>=0&&v.scale()<=3&&v.precision()-v.scale()<=15,"QUANTITY","Số lượng phải không âm và có tối đa 3 chữ số lẻ.");return v;}
}
