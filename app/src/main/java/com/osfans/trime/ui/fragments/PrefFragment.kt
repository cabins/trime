package com.osfans.trime.ui.fragments

import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.osfans.trime.R
import com.osfans.trime.ui.components.SchemaPickerDialog
import com.osfans.trime.ui.main.MainViewModel

class PrefFragment : PreferenceFragmentCompat() {

    private val viewModel : MainViewModel by activityViewModels()

    override fun onResume() {
        super.onResume()
        viewModel.enableTopOptionsMenu()
    }

    override fun onPause() {
        viewModel.disableTopOptionsMenu()
        super.onPause()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        setPreferencesFromResource(R.xml.prefs, rootKey)
        val systemCategory = PreferenceCategory(context).apply {
            setTitle(R.string.system)
            isIconSpaceReserved = false
            order = 18
        }
        preferenceScreen.addPreference(systemCategory)
        systemCategory.addPreference(
            Preference(context).apply {
                setTitle(R.string.settings__system_toolkit)
                setIcon(R.drawable.ic_iconpark_toolkit_24)
                fragment = "com.osfans.trime.ui.fragments.ToolkitFragment"
                order = 19
            }
        )
    }

    override fun onPreferenceTreeClick(preference: Preference?): Boolean {
        return when (preference?.key) {
            "pref_schemas" -> {
                SchemaPickerDialog(requireContext()).show()
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }
}