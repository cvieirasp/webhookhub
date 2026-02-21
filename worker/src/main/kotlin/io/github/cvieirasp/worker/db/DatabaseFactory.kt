package io.github.cvieirasp.worker.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.cvieirasp.shared.config.EnvConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * DatabaseFactory initializes the HikariCP connection pool and wires Exposed ORM
 * to the same PostgreSQL database used by the ingest service.
 *
 * The worker does NOT run Flyway migrations – schema ownership belongs to the API.
 */
object DatabaseFactory {

    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database

    /** Initialises the pool from environment-variable configuration (production entry point). */
    fun init() = init(EnvConfig.Database.url, EnvConfig.Database.user, EnvConfig.Database.password)

    /**
     * Initialises the pool with explicit connection parameters.
     *
     * Used by integration tests, which obtain the JDBC URL, username, and password
     * from a [org.testcontainers.containers.PostgreSQLContainer] rather than from
     * environment variables.
     */
    fun init(jdbcUrl: String, username: String, password: String) {
        dataSource = buildDataSource(jdbcUrl, username, password)
        db = Database.connect(dataSource)
    }

    private fun buildDataSource(jdbcUrl: String, username: String, password: String) =
        HikariDataSource(HikariConfig().apply {
            poolName            = "WebhookHubWorkerPool"
            this.jdbcUrl        = jdbcUrl
            this.username       = username
            this.password       = password
            driverClassName     = "org.postgresql.Driver"

            // Pool sizing: the worker uses a bounded concurrency semaphore for HTTP delivery,
            // so a small pool is sufficient. minimumIdle keeps warm connections ready.
            maximumPoolSize     = 5
            minimumIdle         = 2

            // How long to wait for a connection from the pool before throwing.
            connectionTimeout   = 10_000   // 10 s

            // Release connections that have been idle longer than this.
            idleTimeout         = 600_000  // 10 min

            // Retire connections before the PostgreSQL server-side idle timeout closes them.
            maxLifetime         = 1_800_000 // 30 min

            // Periodically validate idle connections with a lightweight ping so stale
            // sockets are detected before they are handed to the application.
            keepaliveTime       = 30_000   // 30 s

            // Explicit transaction management via Exposed transaction { } blocks.
            isAutoCommit        = false

            // READ_COMMITTED is the right isolation level for a delivery worker:
            // it sees committed rows from the API (new PENDING deliveries) without
            // the overhead of REPEATABLE_READ snapshot maintenance.
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            validate()
        })

    /**
     * Sends a lightweight query to verify the database connection is reachable.
     */
    fun ping(): Boolean = try {
        transaction(db) { exec("SELECT 1") { it.next() } == true }
    } catch (_: Exception) {
        false
    }

    /**
     * Gracefully closes all pooled connections. Call this on application shutdown.
     */
    fun close() {
        if (::dataSource.isInitialized && !dataSource.isClosed) {
            dataSource.close()
        }
    }
}
