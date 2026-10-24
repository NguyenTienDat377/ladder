package com.ladder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LadderApplication {

	public static void main(String[] args) {
		SpringApplication.run(LadderApplication.class, args);
	}

}
