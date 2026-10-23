package dev.kawa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KawaApplication {
    public static void main(String[] args) {
        SpringApplication.run(KawaApplication.class, args);
    }
}
