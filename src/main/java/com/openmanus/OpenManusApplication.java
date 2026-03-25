package com.openmanus;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.openmanus.mapper")
public class OpenManusApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenManusApplication.class, args);
    }
}
