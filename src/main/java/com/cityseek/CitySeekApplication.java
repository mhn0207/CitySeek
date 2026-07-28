package com.cityseek;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.cityseek.mapper")
@SpringBootApplication
public class CitySeekApplication {

    public static void main(String[] args) {
        SpringApplication.run(CitySeekApplication.class, args);
    }

}
