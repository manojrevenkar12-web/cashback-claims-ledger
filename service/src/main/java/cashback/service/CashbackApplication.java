package cashback.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The cashback service: postback and card-event intake, the ledger, missing-cashback claims and the subgraph. */
@SpringBootApplication
public class CashbackApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashbackApplication.class, args);
    }
}
