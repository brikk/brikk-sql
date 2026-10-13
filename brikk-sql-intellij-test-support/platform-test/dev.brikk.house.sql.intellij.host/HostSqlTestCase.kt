package dev.brikk.house.sql.intellij.host

import com.intellij.lang.Language
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStringLiteralExpression
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.completion.configureDialect

/** Host strings are MySQL string literals, whose escaper decodes backslash escapes and doubled quotes. */
abstract class HostSqlTestCase : BasePlatformTestCase() {

    protected val postgres: Language get() = checkNotNull(Language.findLanguageByID("PostgreSQL"))

    /** The whole content of [host] between its quotes, kept. */
    protected fun content(host: PsiLanguageInjectionHost) = HostSqlEntries(listOf(HostSqlEntry(TextRange(1, host.textLength - 1))))

    /** Opens [text] as a MySQL file. */
    protected fun open(text: String) {
        myFixture.configureDialect("MySQL", if ("<caret>" in text) text else "$text<caret>")
    }

    /** The [nth] string literal of the open file. */
    protected fun host(nth: Int = 0): PsiLanguageInjectionHost =
        PsiTreeUtil.findChildrenOfType(myFixture.file, SqlStringLiteralExpression::class.java).elementAt(nth) as PsiLanguageInjectionHost
}
