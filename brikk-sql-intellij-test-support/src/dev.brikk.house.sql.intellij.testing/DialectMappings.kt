package dev.brikk.house.sql.intellij.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.sql.dialects.SqlLanguageDialect
import dev.brikk.house.sql.intellij.dialect.setDialectMapping

/**
 * Maps [file], or the project with a null file, to [dialect] and reparses it, on every supported
 * IDE. With [until], the mapping is cleared when it is disposed.
 */
public fun setSqlDialectMapping(project: Project, file: VirtualFile?, dialect: SqlLanguageDialect?, until: Disposable? = null) {
    setDialectMapping(project, file, dialect)
    if (until != null) Disposer.register(until) { setDialectMapping(project, file, null) }
}
