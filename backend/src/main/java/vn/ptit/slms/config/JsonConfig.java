package vn.ptit.slms.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfig {

    /**
     * Người viết: Khuất Văn Nam
     * Ngày viết: 2026-11-04
     * Use case: Dùng chung cho trao đổi dữ liệu của M01
     *
     * Cấu hình JSON giữ chính xác ID bigint và số lượng BigDecimal khi gửi tới trình duyệt.
     * Xuất các kiểu số này thành chuỗi; sắp xếp khóa Map để payload băm chống lặp ổn định.
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer preciseJson() {
        return b -> b.serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance)
                .serializerByType(BigDecimal.class, ToStringSerializer.instance)
                .featuresToEnable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
}
