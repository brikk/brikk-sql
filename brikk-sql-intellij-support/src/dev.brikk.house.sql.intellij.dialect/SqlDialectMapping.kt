package dev.brikk.house.sql.intellij.dialect

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.util.FileContentUtil
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Maps [file], or the project with a null file, to [dialect], or clears the mapping with a null
 * dialect, and reparses the file. Returns whether the mapping took effect. From 263 the active
 * mapping lives in the workspace model, set through `setDialectMappingSync`; the old setter only
 * updates storage there, so where that function cannot be called the result is false. Call on the EDT.
 */
public fun setDialectMapping(project: Project, file: VirtualFile?, dialect: SqlLanguageDialect?): Boolean = WriteAction.compute<Boolean, RuntimeException> {
    val applied = when (val setter = workspaceSetter) {
        WorkspaceSetter.Absent -> {
            SqlDialectMappings.getInstance(project).setMapping(file, dialect)
            true
        }
        is WorkspaceSetter.Available -> try {
            setter.method.invoke(null, project, file, dialect)
            true
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        } catch (_: IllegalAccessException) {
            storageOnly(project, file, dialect)
        } catch (_: IllegalArgumentException) {
            storageOnly(project, file, dialect)
        }
        WorkspaceSetter.Unusable -> storageOnly(project, file, dialect)
    }
    FileContentUtil.reparseFiles(project, listOfNotNull(file), true)
    applied
}

/** Records the mapping in storage where the workspace model owns the active one: not applied. */
private fun storageOnly(project: Project, file: VirtualFile?, dialect: SqlLanguageDialect?): Boolean {
    SqlDialectMappings.getInstance(project).setMapping(file, dialect)
    return false
}

private sealed interface WorkspaceSetter {
    /** Before 263: the old setter is the mapping. */
    data object Absent : WorkspaceSetter

    class Available(val method: Method) : WorkspaceSetter

    /** The 263 holder class exists, but its setter cannot be called. */
    data object Unusable : WorkspaceSetter
}

private val workspaceSetter: WorkspaceSetter by lazy {
    val holder = try {
        Class.forName("com.intellij.sql.dialects.SqlDialectMappingUtilsKt")
    } catch (_: ClassNotFoundException) {
        return@lazy WorkspaceSetter.Absent
    } catch (_: LinkageError) {
        return@lazy WorkspaceSetter.Unusable
    }
    try {
        WorkspaceSetter.Available(holder.getMethod("setDialectMappingSync", Project::class.java, VirtualFile::class.java, SqlLanguageDialect::class.java))
    } catch (_: NoSuchMethodException) {
        WorkspaceSetter.Unusable
    } catch (_: LinkageError) {
        WorkspaceSetter.Unusable
    }
}
