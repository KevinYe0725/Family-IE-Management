#!/usr/bin/env python3
"""Root-only, stopped-app transfer into an initialized, dedicated PostgreSQL database.

Keeps exact numeric strings, UTC instants, explicit IDs, all foreign keys and generated
columns. Never copies Flyway history or touches another database. Requires mysql CLI
and the existing PostgreSQL Docker container; no third-party Python packages.
"""
import argparse
import csv
import datetime as dt
import decimal
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess


def ident(value):
    if not re.fullmatch(r"[a-z_][a-z0-9_]*", value):
        raise ValueError("Unsafe SQL identifier")
    return value


def run(command, data=None):
    result = subprocess.run(command, input=data, text=True, encoding="utf-8",
                            capture_output=True, timeout=180)
    if result.returncode:
        log_dir = os.environ.get("FAMILY_MIGRATION_LOG_DIR")
        if log_dir:
            with (Path(log_dir) / "command-errors.log").open("a") as log:
                log.write(result.stderr)
        # Do not echo SQL, row data or credentials on failure.
        raise RuntimeError(f"{command[0]} failed (exit {result.returncode}); inspect private migration logs")
    return result.stdout


def canonical(value, kind):
    if value is None:
        return None
    if kind == "boolean":
        if str(value).lower() in ("1", "true", "t"):
            return "true"
        if str(value).lower() in ("0", "false", "f"):
            return "false"
        raise ValueError("Invalid legacy boolean")
    if kind in ("bigint", "integer", "smallint", "numeric", "decimal"):
        number = decimal.Decimal(str(value))
        if not number.is_finite():
            raise ValueError("Invalid monetary value")
        return format(number.normalize(), "f")
    if kind.startswith("timestamp"):
        instant = dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if kind == "timestamp with time zone":
            instant = instant.replace(tzinfo=dt.timezone.utc) if instant.tzinfo is None else instant.astimezone(dt.timezone.utc)
        return instant.isoformat(timespec="microseconds")
    return str(value)


def digest(rows, columns):
    hashes = []
    for row in rows:
        values = [canonical(v, c["data_type"]) for v, c in zip(row, columns)]
        hashes.append(hashlib.sha256(json.dumps(values, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest())
    return hashlib.sha256("".join(sorted(hashes)).encode()).hexdigest()


class Migration:
    def __init__(self, args):
        self.args = args
        ident(args.mysql_database)
        ident(args.pg_database)
        if args.pg_database in ("iwc", "postgres", "template0", "template1"):
            raise ValueError("A dedicated family database is required")
        self.pg = ["docker", "exec", "-i", args.pg_container, "psql", "-X", "-q",
                   "-v", "ON_ERROR_STOP=1", "-U", args.pg_admin, "-d", args.pg_database]

    def mysql(self, sql):
        return run(["mysql", "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4",
                    "--database", self.args.mysql_database, "-e", "set time_zone='+00:00'; " + sql])

    def query(self, sql):
        raw = run(self.pg + ["-At", "-c", "set timezone='UTC'; " + sql])
        return [json.loads(line) for line in raw.splitlines() if line.startswith(("[", "{"))]

    def export(self):
        tables = self.query("select row_to_json(t) from (select table_name from information_schema.tables "
                            "where table_schema='public' and table_type='BASE TABLE' and table_name<>'flyway_schema_history' "
                            "order by table_name) t")
        source = set(self.mysql("select TABLE_NAME from information_schema.TABLES where TABLE_SCHEMA=database() "
                                "and TABLE_TYPE='BASE TABLE' and TABLE_NAME<>'flyway_schema_history'").splitlines())
        if source != {t["table_name"] for t in tables}:
            raise ValueError("Business table sets differ")
        result = {}
        for table in tables:
            name = ident(table["table_name"])
            columns = self.query("select row_to_json(t) from (select column_name,data_type,is_generated,is_identity "
                                 "from information_schema.columns where table_schema='public' and table_name='" + name +
                                 "' order by ordinal_position) t")
            source_columns = set(self.mysql("select COLUMN_NAME from information_schema.COLUMNS where TABLE_SCHEMA=database() "
                                            f"and TABLE_NAME='{name}'").splitlines())
            if source_columns != {c["column_name"] for c in columns}:
                raise ValueError(f"Column sets differ: {name}")
            projection = ",".join(f"cast(`{ident(c['column_name'])}` as char)" for c in columns)
            rows = [json.loads(line) for line in self.mysql(f"select json_array({projection}) from `{name}`").splitlines()]
            auto = self.mysql("select coalesce(AUTO_INCREMENT,1) from information_schema.TABLES "
                              f"where TABLE_SCHEMA=database() and TABLE_NAME='{name}'").strip()
            result[name] = {"columns": columns, "rows": rows, "next_id": int(auto), "digest": digest(rows, columns)}
        return result

    def verify(self, data):
        report = {}
        for name, entry in data.items():
            projection = ",".join('"' + ident(c["column_name"]) + '"::text' for c in entry["columns"])
            rows = self.query(f'select json_build_array({projection}) from "{name}"')
            actual = digest(rows, entry["columns"])
            if len(rows) != len(entry["rows"]) or actual != entry["digest"]:
                raise ValueError(f"Row verification failed: {name}")
            report[name] = {"rows": len(rows), "sha256": actual, "verified": True}
        return report

    def apply(self, data):
        if subprocess.run(["systemctl", "is-active", "--quiet", "family-finance.service"]).returncode == 0:
            raise ValueError("Stop only family-finance before taking the final snapshot")
        history = self.query("select json_build_array(max(version::int),bool_and(success)) from flyway_schema_history")
        if history != [[47, True]]:
            raise ValueError("Target must have all 47 PostgreSQL migrations")
        # Allow only empty business tables and the three singleton rows inserted by migrations.
        for name in data:
            rows = self.query(f'select json_build_array(count(*)) from "{name}"')[0][0]
            if rows and name not in ("fx_sync_state", "fx_history_coverage", "security_catalog_state"):
                raise ValueError(f"Target is not fresh: {name}")
        foreign_keys = self.query("select row_to_json(t) from (select c.conname,r.relname as table_name,c.condeferrable,c.condeferred "
                                  "from pg_constraint c join pg_class r on r.oid=c.conrelid join pg_namespace n on n.oid=r.relnamespace "
                                  "where c.contype='f' and n.nspname='public') t")
        sql = io.StringIO()
        sql.write("begin; set timezone='UTC';\n")
        for fk in foreign_keys:
            sql.write(f'alter table "{ident(fk["table_name"])}" alter constraint "{ident(fk["conname"])}" deferrable initially deferred;\n')
        sql.write("truncate " + ",".join('"' + n + '"' for n in data) + " restart identity;\n")
        for name, entry in data.items():
            writable = [(i, c) for i, c in enumerate(entry["columns"]) if c["is_generated"] == "NEVER"]
            cols = ",".join('"' + ident(c["column_name"]) + '"' for _, c in writable)
            sql.write(f'copy "{name}" ({cols}) from stdin with (format csv, null \'\\N\');\n')
            for row in entry["rows"]:
                cells = []
                for i, col in writable:
                    value = canonical(row[i], col["data_type"])
                    if value is None:
                        cells.append("\\N")
                    else:
                        out = io.StringIO()
                        csv.writer(out, quoting=csv.QUOTE_ALL, lineterminator="").writerow([value])
                        cells.append(out.getvalue())
                sql.write(",".join(cells) + "\n")
            sql.write("\\.\n")
            for _, c in writable:
                if c["is_identity"] == "YES":
                    col = ident(c["column_name"])
                    sql.write(f"select setval(pg_get_serial_sequence('{name}','{col}'), greatest({entry['next_id']},coalesce((select max(\"{col}\")+1 from \"{name}\"),1)),false);\n")
        sql.write("set constraints all immediate;\n")
        for fk in foreign_keys:
            state = ("deferrable initially deferred" if fk["condeferred"] else "deferrable initially immediate") if fk["condeferrable"] else "not deferrable"
            sql.write(f'alter table "{ident(fk["table_name"])}" alter constraint "{ident(fk["conname"])}" {state};\n')
        sql.write("commit;\n")
        run(self.pg, sql.getvalue())
        return self.verify(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql-database", default="family_finance")
    parser.add_argument("--pg-database", default="family_finance")
    parser.add_argument("--pg-container", default="ielts-writing-coach-postgres-1")
    parser.add_argument("--pg-admin", default="iwc")
    parser.add_argument("--backup-dir", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    args.backup_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.environ["FAMILY_MIGRATION_LOG_DIR"] = str(args.backup_dir)
    migration = Migration(args)
    if args.apply and subprocess.run(["systemctl", "is-active", "--quiet", "family-finance.service"]).returncode == 0:
        raise ValueError("Application must be stopped before snapshot")
    data = migration.export()
    (args.backup_dir / "legacy-rows.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    report = migration.apply(data) if args.apply else {n: {"rows": len(e["rows"])} for n, e in data.items()}
    (args.backup_dir / "verification.json").write_text(json.dumps(report, indent=2))
    print(json.dumps({"tables": len(report), "rows": sum(e["rows"] for e in report.values()),
                      "verified": args.apply, "backup": str(args.backup_dir)}))


if __name__ == "__main__":
    main()
