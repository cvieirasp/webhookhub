package io.github.cvieirasp.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.testcontainers.containers.PostgreSQLContainer

/**
 * This file contains the setup for the test database using Testcontainers and Flyway.
 * It defines a custom PostgreSQL container and a function to initialize the database with migrations.
 */
internal class TestPostgresContainer(image: String) : PostgreSQLContainer<TestPostgresContainer>(image)

/**
 * Sets up the test database by applying Flyway migrations and configuring a HikariDataSource.
 * This function is used in the test setup to ensure that the database is ready for testing.
 *
 * @param postgres The PostgreSQL container instance to connect to.
 * @return A Database instance connected to the test database with applied migrations.
 */
internal fun setupTestDatabase(postgres: PostgreSQLContainer<*>): Database {
    Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .load()
        .migrate()

    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = postgres.jdbcUrl
        username = postgres.username
        password = postgres.password
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 5
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    })

    return Database.connect(dataSource)
}
