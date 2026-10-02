package com.aicompany.core.database;

import java.sql.Connection;
import java.sql.SQLException;

/** Abre una conexión JDBC real; {@code databaseOverride} null = la base de la conexión (p. ej. "postgres" para crearla). */
public interface PostgresConnector {

    Connection open(DatabaseConnection c, String password, String databaseOverride) throws SQLException;
}
