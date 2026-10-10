package com.goldcatcher;
import com.goldcatcher.persistence.DocumentStore;
import java.time.*;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class TestSupport {
    public static final class TestClock extends Clock {
        public Instant now=Instant.parse("2026-10-08T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
        public void advance(long millis){now=now.plusMillis(millis);}
    }
    public final TestClock clock=new TestClock();public final ObjectMapper mapper=JsonMapper.builder().build();
    public final MockEnvironment environment=new MockEnvironment();public final RuntimeSettings settings=new RuntimeSettings(environment,mapper);
    public final DocumentStore store;
    public TestSupport() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__durable_runtime.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__market_archive.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__account_samples.sql")).execute(ds);
        store=new DocumentStore(new JdbcTemplate(ds),mapper,new TransactionTemplate(new DataSourceTransactionManager(ds)),clock);
    }
}
