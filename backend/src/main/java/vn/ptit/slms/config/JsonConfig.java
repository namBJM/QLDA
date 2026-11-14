package vn.ptit.slms.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
@Configuration public class JsonConfig {
 @Bean Jackson2ObjectMapperBuilderCustomizer preciseJson(){return b->b.serializerByType(Long.class,ToStringSerializer.instance).serializerByType(Long.TYPE,ToStringSerializer.instance).serializerByType(BigDecimal.class,ToStringSerializer.instance).featuresToEnable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);}
}
