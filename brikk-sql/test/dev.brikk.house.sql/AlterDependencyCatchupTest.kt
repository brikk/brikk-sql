package dev.brikk.house.sql

import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AlterDependencyCatchupTest {
    @Test
    fun mixedOperationsAndAlternatingOptionsRemainCompleteStructuredAlters() {
        for (dialect in listOf(Dialects.MYSQL, Dialects.POSTGRES)) {
            val tree = assertIs<Alter>(dialect.parseOne("ALTER TABLE t ADD COLUMN a INT, DROP COLUMN b, ADD COLUMN c INT"))
            assertEquals(3, (tree.args["actions"] as List<*>).size)
            assertEquals(tree, Serde.load(Serde.dump(tree)))
            assertIs<Alter>(dialect.parseOne(tree.sql(dialect.name)))
        }
        for (source in listOf("ALTER TABLE t ENGINE=InnoDB, ADD COLUMN a INT, COMMENT='hi', ADD COLUMN b INT",
            "ALTER TABLE t COMMENT='hi' AUTO_INCREMENT=5, ADD COLUMN a INT, ENGINE=InnoDB")) {
            val tree = assertIs<Alter>(Dialects.MYSQL.parseOne(source))
            assertEquals(if ("ADD COLUMN b" in source) 2 else 1, (tree.args["actions"] as List<*>).size)
            assertEquals(tree, Dialects.MYSQL.parseOne(tree.sql("mysql")))
        }
    }

    @Test
    fun optionsOnlyBareDropsAndKeywordNamedColumnsKeepTheirMeaning() {
        for (source in listOf("ALTER TABLE t ENGINE=InnoDB COMMENT='hi'", "ALTER TABLE t COMMENT='hi', AUTO_INCREMENT=10")) {
            val tree = assertIs<Alter>(Dialects.MYSQL.parseOne(source))
            assertEquals(emptyList<Any>(), tree.args["actions"])
            assertEquals(2, (tree.args["options"] as List<*>).size)
            assertEquals(tree, Dialects.MYSQL.parseOne(tree.sql("mysql")))
        }
        assertEquals("ALTER TABLE t DROP COLUMN c", Dialects.MYSQL.parseOne("ALTER TABLE t DROP c").sql("mysql"))
        assertIs<Alter>(Dialects.POSTGRES.parseOne("ALTER TABLE t ALTER COLUMN add TYPE BIGINT"))
        val unsupported = "ALTER TABLE t ADD COLUMN a INT, SOMETHING_UNSUPPORTED b"
        assertIs<Command>(Dialects.MYSQL.parseOne(unsupported))
        assertEquals(unsupported, Dialects.MYSQL.parseOne(unsupported).sql("mysql"))
    }
}
