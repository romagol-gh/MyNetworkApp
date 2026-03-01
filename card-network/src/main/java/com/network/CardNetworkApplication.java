package com.network;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CardNetworkApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardNetworkApplication.class, args);
    }
}
