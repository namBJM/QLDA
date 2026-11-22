package vn.ptit.slms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SlmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SlmsApplication.class, args);
    }
}
