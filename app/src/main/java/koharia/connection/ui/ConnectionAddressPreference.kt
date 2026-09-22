package koharia.connection.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.preference.DialogPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText
import koharia.connection.ConnectionAddressRouter
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

class ConnectionAddressPreference(context: Context, addressKey: String) : DialogPreference(context) {
    init {
        key = addressKey
        title = context.stringResource(MR.strings.komga_pref_address_title)
        dialogTitle = title
        positiveButtonText = context.stringResource(MR.strings.action_ok)
        negativeButtonText = context.stringResource(MR.strings.action_cancel)
    }

    val publicAddress: String get() = read(key)
    val internalAddress: String get() = read(ConnectionAddressRouter.INTERNAL_ADDRESS_KEY)

    private fun read(key: String): String =
        preferenceManager.preferenceDataStore?.getString(key, "")
            ?: sharedPreferences?.getString(key, "").orEmpty()

    fun save(publicAddress: String, internalAddress: String) {
        val store = preferenceManager.preferenceDataStore
        if (store != null) {
            store.putString(key, publicAddress)
            store.putString(ConnectionAddressRouter.INTERNAL_ADDRESS_KEY, internalAddress)
        } else {
            sharedPreferences?.edit()?.putString(key, publicAddress)
                ?.putString(ConnectionAddressRouter.INTERNAL_ADDRESS_KEY, internalAddress)?.apply()
        }
        summary = publicAddress
    }
}

class ConnectionAddressPreferenceDialog : PreferenceDialogFragmentCompat() {
    private lateinit var publicInput: TachiyomiTextInputEditText
    private lateinit var internalInput: TachiyomiTextInputEditText
    private var savedPublic: String? = null
    private var savedInternal: String? = null
    private var advancedExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedPublic = savedInstanceState?.getString("publicAddress")
        savedInternal = savedInstanceState?.getString("internalAddress")
        advancedExpanded = savedInstanceState?.getBoolean("advancedExpanded") ?: false
    }

    override fun onCreateDialogView(context: Context): View {
        val pref = preference as ConnectionAddressPreference
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        fun input(label: String, value: String, help: String? = null): TachiyomiTextInputEditText {
            val layout = TextInputLayout(context).apply {
                hint = label
                helperText = help
                setPadding(0, padding / 2, 0, padding / 2)
            }
            return TachiyomiTextInputEditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
                setText(value)
                layout.addView(this)
                fields.addView(layout)
            }
        }
        publicInput =
            input(context.stringResource(MR.strings.connection_public_address), savedPublic ?: pref.publicAddress)
        val advancedButton = android.widget.Button(
            context,
            null,
            com.google.android.material.R.attr.borderlessButtonStyle,
        ).apply {
            text = context.stringResource(MR.strings.connection_address_advanced)
            isAllCaps = false
            val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            try {
                colors.getColorStateList(0)?.let(::setTextColor)
            } finally {
                colors.recycle()
            }
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        fields.addView(advancedButton)
        internalInput = input(
            context.stringResource(MR.strings.connection_internal_address),
            savedInternal ?: pref.internalAddress,
            context.stringResource(MR.strings.connection_internal_address_summary),
        )
        val internalLayout = fields.getChildAt(fields.childCount - 1)
        internalLayout.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        advancedButton.setOnClickListener {
            advancedExpanded = !advancedExpanded
            internalLayout.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        }
        publicInput.doAfterTextChanged { updateValidation() }
        internalInput.doAfterTextChanged { updateValidation() }
        return ScrollView(context).apply { addView(fields) }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = super.onCreateDialog(savedInstanceState).also {
        it.setOnShowListener { updateValidation() }
    }

    private fun updateValidation() {
        val validPublic = ConnectionAddressRouter.normalize(publicInput.text.toString()) != null
        val validInternal = internalInput.text.isNullOrBlank() ||
            ConnectionAddressRouter.normalize(internalInput.text.toString()) != null
        val message = requireContext().stringResource(MR.strings.komga_pref_address_validation)
        publicInput.error = message.takeIf { !validPublic && !publicInput.text.isNullOrBlank() }
        internalInput.error = message.takeIf { !validInternal }
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = validPublic && validInternal
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("publicAddress", publicInput.text.toString())
        outState.putString("internalAddress", internalInput.text.toString())
        outState.putBoolean("advancedExpanded", advancedExpanded)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            (preference as ConnectionAddressPreference).save(
                publicInput.text.toString().trim(),
                internalInput.text.toString().trim(),
            )
        }
    }
}
