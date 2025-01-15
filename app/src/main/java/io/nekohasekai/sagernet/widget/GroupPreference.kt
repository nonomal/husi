package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import io.nekohasekai.sagernet.database.SagerDatabase
import rikka.preference.SimpleMenuPreference

class GroupPreference
@JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = androidx.preference.R.attr.dropdownPreferenceStyle,
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    init {
        val groups = SagerDatabase.groupDao.allGroups()

        entries = groups.map { it.displayName() }.toTypedArray()
        entryValues = groups.map { "${it.id}" }.toTypedArray()
    }

    override fun getSummary(): CharSequence? {
        if (!value.isNullOrBlank() && value != "0") {
            return SagerDatabase.groupDao.getById(value.toLong())?.displayName()
                ?: super.getSummary()
        }
        return super.getSummary()
    }

}