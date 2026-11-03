package kz.calories;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CaloriesApplication {
    public static void main(String[] args) {
        SpringApplication.run(CaloriesApplication.class, args);
    }
}
