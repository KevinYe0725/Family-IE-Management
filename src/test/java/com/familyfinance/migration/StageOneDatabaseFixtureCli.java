package com.familyfinance.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Test/CI-only entry point for producing the real Stage 1 migration fixture. */
public final class StageOneDatabaseFixtureCli {

    private StageOneDatabaseFixtureCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one absolute H2 database base path");
        }

        Path requestedDatabase = Path.of(args[0]);
        if (!requestedDatabase.isAbsolute()) {
            throw new IllegalArgumentException("The H2 database base path must be absolute");
        }
        Path database = requestedDatabase.normalize();
        Files.createDirectories(database.getParent());
        if (Files.exists(Path.of(database.toString() + ".mv.db"))) {
            throw new IllegalStateException("Refusing to overwrite an existing Stage 1 fixture: " + database);
        }

        StageOneDatabaseFixture.create(database);
        try (Connection connection = DriverManager.getConnection(MigrationTestSupport.h2Url(database), "sa", "");
                PreparedStatement statement = connection.prepareStatement(
                        "update app_users set password_hash = ? where username = 'demo'")) {
            statement.setString(1, new BCryptPasswordEncoder().encode("demo1234"));
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Could not prepare the demo login in the Stage 1 fixture");
            }
        }

        System.out.println(Path.of(database.toString() + ".mv.db"));
    }
}
