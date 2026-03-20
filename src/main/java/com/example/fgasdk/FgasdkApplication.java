package com.example.fgasdk;

import com.example.fgasdk.config.FgaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FgaProperties.class)
public class FgasdkApplication {

	public static void main(String[] args) {
		SpringApplication.run(FgasdkApplication.class, args);
	}

}
