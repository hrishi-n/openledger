package dev.openledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OpenLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenLedgerApplication.class, args);
    }
}
