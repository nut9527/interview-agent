package cn.edu.whu.zhizhida;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ZhizhidaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhizhidaApplication.class, args);
    }

}
