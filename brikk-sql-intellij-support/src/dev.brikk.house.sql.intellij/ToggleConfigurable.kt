package dev.brikk.house.sql.intellij

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel
import org.jetbrains.annotations.ApiStatus

/**
 * A settings page with one checkbox for [settings]. `-D<vetoProperty>=false` disables the checkbox
 * and says why. Register a no-arg or project-argument subclass whose id matches [id].
 */
@ApiStatus.Experimental
public abstract class ToggleConfigurable(
    private val id: String,
    private val displayName: String,
    private val checkboxText: String,
    private val help: String? = null,
    private val vetoProperty: String? = null,
) : SearchableConfigurable {
    private var checkbox: JBCheckBox? = null

    protected abstract val settings: ToggleSettings

    final override fun getId(): String = id
    final override fun getDisplayName(): String = displayName

    final override fun createComponent(): JComponent {
        val vetoed = vetoProperty != null && System.getProperty(vetoProperty).equals("false", ignoreCase = true)
        val box = JBCheckBox(checkboxText).apply { isEnabled = !vetoed }
        checkbox = box
        reset()
        return JPanel(VerticalLayout(JBUI.scale(8))).apply {
            add(box)
            (if (vetoed) "Disabled by the VM option -D$vetoProperty=false." else help)?.let { add(JBLabel(it)) }
        }
    }

    final override fun isModified(): Boolean = checkbox?.let { it.isSelected != settings.enabled } ?: false
    final override fun apply() {
        checkbox?.let { settings.enabled = it.isSelected }
    }
    final override fun reset() {
        checkbox?.isSelected = settings.enabled
    }
    final override fun disposeUIResources() {
        checkbox = null
    }
}
