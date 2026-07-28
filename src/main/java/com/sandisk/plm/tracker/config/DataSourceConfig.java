package com.sandisk.plm.tracker.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Primary
    @Bean(name = "dataSource")
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = "customDataSource")
    @ConfigurationProperties(prefix = "custom.datasource")
    public DataSource customDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /** i2 / Blue Yonder supply-chain DB (PDC schema). Read-only, used for the
     *  ECO Timeline "Primary #" column. Lazy + fail-soft: initialization-fail-timeout
     *  is -1 so a missing/unreachable PDC never blocks app startup. */
    @Bean(name = "pdcDataSource")
    @ConfigurationProperties(prefix = "pdc.datasource")
    public DataSource pdcDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }
}
