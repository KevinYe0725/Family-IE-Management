import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("postgres_migration", Path(__file__).parents[1] / "migrate_mysql_to_postgresql.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class PostgreSqlMigrationTest(unittest.TestCase):
    def test_money_and_signed_long_cents_stay_exact(self):
        self.assertEqual(m.canonical("92233720368547758.07", "numeric"), "92233720368547758.07")
        self.assertEqual(m.canonical("-9223372036854775808", "bigint"), "-9223372036854775808")
        with self.assertRaises(ValueError):
            m.canonical("NaN", "numeric")

    def test_utc_and_boolean_representations_compare_equally(self):
        cols = [{"data_type": "timestamp with time zone"}, {"data_type": "boolean"}, {"data_type": "numeric"}]
        self.assertEqual(m.digest([["2026-10-03 08:00:00.123456", "1", "10.00"]], cols),
                         m.digest([["2026-10-03 16:00:00.123456+08:00", "true", "10"]], cols))

    def test_row_order_ignores_order_but_detects_loss_duplicates_and_null(self):
        cols = [{"data_type": "text"}]
        self.assertEqual(m.digest([[None], ["\\N"], ["line\nquote\""]], cols),
                         m.digest([["line\nquote\""], ["\\N"], [None]], cols))
        self.assertNotEqual(m.digest([[None]], cols), m.digest([[""]], cols))
        self.assertNotEqual(m.digest([["x"]], cols), m.digest([["x"], ["x"]], cols))

    def test_sql_identifiers_reject_injection(self):
        for value in ("iwc; drop database iwc", "a'", 'a"', "schema.table"):
            with self.assertRaises(ValueError):
                m.ident(value)


if __name__ == "__main__":
    unittest.main()
