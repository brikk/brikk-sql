package dev.brikk.house.sql.intellij.dialect

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.util.FileContentUtil
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.jetbrains.annotations.ApiStatus

/**
 * Maps [file], or the project with a null file, to [dialect], or clears the mapping with a null
 * dialect, and reparses the file. From 263 the active mapping lives in the workspace model and the
 * old setter only updates storage, so the 263 setter is used where it exists. Call on the EDT.
 */
@ApiStatus.Experimental
public fun setDialectMapping(project: Project, file: VirtualFile?, dialect: SqlLanguageDialect?) {
    WriteAction.run<RuntimeException> {
        val setter = workspaceSetter
        if (setter == null) SqlDialectMappings.getInstance(project).setMapping(file, dialect)
        else try {
            setter.invoke(null, project, file, dialect)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        FileContentUtil.reparseFiles(project, listOfNotNull(file), true)
    }
}

/** `setDialectMappingSync(Project, VirtualFile?, SqlLanguageDialect?)`, public in 263 and absent before. */
private val workspaceSetter: Method? by lazy {
    try {
        Class.forName("com.intellij.sql.dialects.SqlDialectMappingUtilsKt").methods
            .singleOrNull { it.name == "setDialectMappingSync" && it.parameterCount == 3 }
    } catch (_: ClassNotFoundException) {
        null
    }
}
