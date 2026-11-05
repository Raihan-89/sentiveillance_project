package com.raihan.sentiveillance_project;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Loading the full context starts the real pipeline: it needs the licensing daemon, the
 * native libraries and a reachable PostgreSQL. Enable this only when all three are up.
 */
@Disabled("Requires pgd licensing daemon, SDK native libraries and PostgreSQL")
@SpringBootTest
class SentiveillanceProjectApplicationTests {

    @Test
    void contextLoads() {
    }
}
