package dev.brikk.house.sql.intellij.catalog

import com.intellij.database.model.DasObject
import com.intellij.database.model.DataSourceSnapshotManager
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModModel
import org.jetbrains.annotations.ApiStatus

/**
 * Whether [obj] belongs to the platform's builtin catalog of its dbms (the bundled mini-catalog the
 * dialect resolves builtins against), not to a data source's model or the user's code: its model is
 * stored in `DataSourceSnapshotManager.MINICAT_STORAGE`, as the platform itself tells builtin
 * resolve results apart.
 */
@ApiStatus.Experimental
public fun isBuiltinCatalogObject(obj: DasObject?): Boolean {
    val model = (obj as? BasicElement)?.model as? BasicModModel ?: return false
    return model.textStorage === DataSourceSnapshotManager.MINICAT_STORAGE
}
