package dev.kawa;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

// reaper interval is pushed out to an hour so tests trigger reaping explicitly instead of racing it
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "kawa.reaper.interval-ms=3600000")
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {

    @Autowired
    protected JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE jobs").update();
    }
}
