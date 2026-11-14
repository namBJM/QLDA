package vn.ptit.slms.common;
import org.springframework.stereotype.Component;import com.fasterxml.jackson.databind.ObjectMapper;import java.util.*;
@Component public class Json {private final ObjectMapper mapper; public Json(ObjectMapper mapper){this.mapper=mapper;}
 public String write(Object x){try{return mapper.writeValueAsString(x);}catch(Exception e){throw new IllegalArgumentException(e);}}
 @SuppressWarnings("unchecked") public Map<String,Object> read(String x){try{return mapper.readValue(x,Map.class);}catch(Exception e){throw new IllegalArgumentException(e);}}
}
