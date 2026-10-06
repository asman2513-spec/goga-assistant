package app.goga.server.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

internal class Sql(private val connection: Connection) {
    fun update(sql: String, vararg args: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeUpdate()
        }

    fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
        }

    fun <T> list(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(map(rs))
                }
            }
        }

    private fun bind(statement: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { index, arg ->
            val column = index + 1
            when (arg) {
                null -> statement.setNull(column, Types.NULL)
                is Int -> statement.setInt(column, arg)
                is Long -> statement.setLong(column, arg)
                else -> statement.setString(column, arg.toString())
            }
        }
    }
}

internal fun isUniqueViolation(error: SQLException): Boolean {
    if (error.sqlState == "23505") return true
    return error.message.orEmpty().contains("UNIQUE", ignoreCase = true)
}
