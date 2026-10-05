package com.tcrm.admin;

import java.util.TimeZone;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@MapperScan(
    value = {
      "com.tcrm.admin.system",
      "com.tcrm.admin.task",
      "com.tcrm.admin.kol",
      "com.tcrm.admin.product"
    },
    annotationClass = org.apache.ibatis.annotations.Mapper.class)
@SpringBootApplication
public class TcrmAdminApplication {
  public static void main(String[] args) {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(TcrmAdminApplication.class, args);
  }
}
