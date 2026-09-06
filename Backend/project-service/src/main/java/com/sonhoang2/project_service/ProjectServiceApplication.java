package com.sonhoang2.project_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

@EnableFeignClients(basePackages = "com.sonhoang2.project_service.project.feign")
@SpringBootApplication
@ComponentScan(basePackages = {"com.sonhoang2.project_service", "com.sonhoang2.common"})
public class ProjectServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProjectServiceApplication.class, args);
	}

}
