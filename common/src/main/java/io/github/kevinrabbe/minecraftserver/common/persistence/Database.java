package io.github.kevinrabbe.minecraftserver.common.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/** Owns the process-local PostgreSQL connection pool and schema migration lifecycle. */
public final class Database implements AutoCloseable {
    private static final String POSTGRESQL_DRIVER = "org.postgresql.Driver";

    private final HikariDataSource dataSource;

    private Database(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        BackendRegistry.installProcessDataSource(dataSource);
    }

    public static Database open(DatabaseConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setDriverClassName(POSTGRESQL_DRIVER);
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setMinimumIdle(0);
        hikari.setConnectionTimeout(5_000);
        hikari.setValidationTimeout(3_000);
        hikari.setInitializationFailTimeout(5_000);
        hikari.setPoolName("minecraft-network-postgres");

        return new Database(new HikariDataSource(hikari));
    }

    public void migrate() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .load()
                .migrate();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        BackendRegistry.clearProcessDataSource(dataSource);
        dataSource.close();
    }
}
