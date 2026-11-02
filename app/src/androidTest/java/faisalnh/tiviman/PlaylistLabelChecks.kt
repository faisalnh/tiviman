package faisalnh.tiviman

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView

/** Opens setup only: no saved-store access, provider requests, or import activation. */
class PlaylistLabelChecks(private val runner: Instrumentation) {
    private fun <T> main(block: () -> T): T {
        var outcome: Result<T>? = null
        runner.runOnMainSync { outcome = runCatching { block() } }
        return checkNotNull(outcome).getOrThrow()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        ((view as? ViewGroup)?.let { group ->
            (0 until group.childCount).flatMap { descendants(group.getChildAt(it)) }
        } ?: emptyList())

    fun run() {
        val result = Bundle()
        val passed = mutableListOf<String>()
        var activity: Activity? = null
        var resultCode = Activity.RESULT_CANCELED
        val labels = listOf("M3U playlist URL", "Xtream server URL", "Stalker portal URL")
        val hints = listOf("http://… or https://…", "http://host:port", "http://host/stalker_portal/c/")
        // Field label to hint. Only the password is masked.
        val providerFields = listOf("Xtream username" to "Xtream username", "Xtream password" to "Xtream password",
            "Authorized Stalker MAC (00:1A:79:…)" to "00:1A:79:…")
        try {
            for (provider in labels.indices) {
                val setup = runner.startActivitySync(Intent(runner.targetContext, PlaylistActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activity = setup
                runner.waitForIdleSync()
                fun views() = descendants(setup.window.decorView)
                fun selector() = views().filterIsInstance<Spinner>().single()
                fun editor(hint: String) = views().filterIsInstance<EditText>().single { it.hint == hint }
                fun labelFor(editor: EditText) = views().filterIsInstance<TextView>().single {
                    it !is EditText && editor.id != View.NO_ID && it.labelFor == editor.id
                }
                fun addressLabel() = views().filterIsInstance<TextView>().single {
                    it !is EditText && it.text.toString() in labels
                }
                fun address(): EditText = setup.findViewById(addressLabel().labelFor)
                fun assertLabel(editor: EditText, title: String, shown: Boolean, state: String) {
                    val label = labelFor(editor)
                    val context = "$title [$state, expectedShown=$shown]"
                    check(label.text.toString() == title) { "$context: label mismatch" }
                    check(!label.isFocusable && !label.isClickable) { "$context: label adds a D-pad stop" }
                    check(label.isShown == shown) { "$context: label visibility mismatch" }
                    check(editor.isShown == shown) { "$context: editor visibility mismatch" }
                    if (shown) check(label.width > 0 && label.height > 0) { "$context: label has no layout bounds" }
                    val parent = label.parent as ViewGroup
                    check(editor.parent === parent && parent.indexOfChild(editor) == parent.indexOfChild(label) + 1) {
                        "$context: label does not precede editor"
                    }
                    check(editor.contentDescription.isNullOrEmpty()) { "$context: description overrides native editor semantics" }
                    check(editor.isFocusable && editor.isFocusableInTouchMode) { "$context: editor is not focusable" }
                }
                fun assertNativeEditor(editor: EditText, title: String, private: Boolean, state: String, sensitive: Boolean = private) {
                    val context = "$title [$state, shown=${editor.isShown}, visibility=${editor.visibility}, " +
                        "inputType=${editor.inputType}, transformation=${editor.transformationMethod?.javaClass?.name}]"
                    // GONE ancestors can prevent native accessibility-node initialization; test semantics only when shown.
                    check(editor.isShown) { "$context: native semantics require a shown editor" }
                    val node = editor.createAccessibilityNodeInfo()
                    try {
                        check(node.isEditable) { "$context: accessibility isEditable=false" }
                        check(node.className?.toString() == EditText::class.java.name) {
                            "$context: accessibility className=${node.className}"
                        }
                        check(node.isPassword == private) { "$context: accessibility isPassword=${node.isPassword}, expected=$private" }
                    } finally {
                        @Suppress("DEPRECATION")
                        node.recycle()
                    }
                    if (private) {
                        check(editor.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
                            "$context: password inputType mismatch (${editor.inputType})"
                        }
                        check(editor.transformationMethod is PasswordTransformationMethod) { "$context: password transformation missing" }
                        val value = editor.text
                        val masked = editor.transformationMethod.getTransformation(value, editor)
                        check(masked.length == value.length && masked.indices.all { masked[it] == '\u2022' }) {
                            "$context: rendered text is not fully masked"
                        }
                    } else {
                        check(editor.transformationMethod !is PasswordTransformationMethod) { "$context: plain field is masked" }
                        check(editor.inputType and InputType.TYPE_MASK_VARIATION != InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                            "$context: plain field uses a password input type"
                        }
                    }
                    // URLs, usernames and MACs are visible but still kept out of saved state and keyboard learning.
                    if (sensitive) {
                        check(!editor.isSaveEnabled) { "$context: saved view state enabled" }
                        check(editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) {
                            "$context: IME learning allowed"
                        }
                    }
                    check(setup.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                        "$context: setup allows capture"
                    }
                }
                fun assertFields(selection: Int, panelShown: Boolean, state: String) {
                    val address = address()
                    check(address.hint.toString() == hints[selection]) { "Provider hint mismatch: $selection [$state]" }
                    assertLabel(address, labels[selection], panelShown, state)
                    val name = editor("Playlist name")
                    assertLabel(name, "Playlist name", true, state)
                    assertNativeEditor(name, "Playlist name", false, state)
                    if (panelShown) {
                        assertNativeEditor(address, labels[selection], false, state, sensitive = true)
                        check(address.inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_URI) {
                            "${labels[selection]} [$state]: address is not a URL field"
                        }
                    }
                    for ((index, field) in providerFields.withIndex()) {
                        val (title, hint) = field
                        val view = editor(hint)
                        val active = if (index < 2) selection == 1 else selection == 2
                        check((view.parent as View).visibility == if (active) View.VISIBLE else View.GONE) {
                            "$title [$state]: provider field group visibility mismatch"
                        }
                        assertLabel(view, title, panelShown && active, state)
                        if (panelShown && active) assertNativeEditor(view, title, index == 1, state, sensitive = true)
                    }
                }
                // Revisit all choices before Next to catch stale labels and orphaned secret labels.
                for (selection in listOf(0, 1, 2, 0, provider)) {
                    main { selector().setSelection(selection) }
                    runner.waitForIdleSync()
                    main { assertFields(selection, false, "before Next, selection=$selection") }
                }
                main { check(views().filterIsInstance<Button>().single { it.text == "Next" }.performClick()) }
                runner.waitForIdleSync()
                main {
                    assertFields(provider, true, "empty")
                    check(!selector().isEnabled && setup.currentFocus === address()) { "Next did not focus address" }
                    check(views().filterIsInstance<EditText>().all { it.text.isEmpty() }) { "Setup fields are not empty" }
                    val activeEditors = views().filterIsInstance<EditText>().filter { it.isShown }
                    for (field in activeEditors) field.setText(if (field === address()) {
                        "https://synthetic.invalid/setup?token=synthetic-only"
                    } else "synthetic-only")
                    assertFields(provider, true, "populated")
                    for (field in activeEditors) check(!labelFor(field).text.contains("synthetic-only")) {
                        "Visible label includes synthetic field value"
                    }
                    if (provider == 1) {
                        val field = editor("Xtream password")
                        fun toggle() = views().filterIsInstance<Button>().single { it.text.endsWith(" password") }
                        check(toggle().isShown && toggle().isFocusable && toggle().text == "Show password") { "Show password button missing" }
                        check(toggle().contentDescription == toggle().text) { "Toggle has no matching spoken name" }
                        fun icon() = toggle().compoundDrawablesRelative[0]
                        check(icon() is PlayerIconDrawable && icon().bounds.width() > 0) { "Toggle has no eye icon" }
                        val shownIcon = icon()
                        check((toggle().parent as ViewGroup).indexOfChild(toggle()) == (field.parent as ViewGroup).indexOfChild(field) + 1) {
                            "Toggle does not follow the password field"
                        }
                        check(toggle().performClick())
                        check(field.transformationMethod == null && toggle().text == "Hide password") { "Show did not reveal the password" }
                        check(icon() is PlayerIconDrawable && icon() !== shownIcon) { "Icon did not switch to the hide state" }
                        check(field.text.toString() == "synthetic-only") { "Reveal changed the value" }
                        check(toggle().performClick())
                        check(field.transformationMethod is PasswordTransformationMethod && toggle().text == "Show password") { "Hide did not mask" }
                        check(icon() === shownIcon) { "Icon did not return to the show state" }
                        check(toggle().performClick() && field.transformationMethod == null)
                        check(field.requestFocus())
                    }
                }
                if (provider == 1) {
                    runner.waitForIdleSync()
                    main {
                        check(editor("Xtream password").transformationMethod == null) { "Moving focus from toggle to its field hid the password" }
                        check(editor("Xtream username").requestFocus())
                    }
                }
                if (provider == 1) {
                    runner.waitForIdleSync()
                    main {
                        check(editor("Xtream password").transformationMethod is PasswordTransformationMethod) {
                            "Password stayed visible after focus left it"
                        }
                        check(views().filterIsInstance<Button>().single { it.text.endsWith(" password") }.text == "Show password")
                    }
                    passed.add("Xtream password: Show/Hide toggle reveals and re-masks without changing the value, has a spoken name, and re-masks when focus leaves")
                }
                main {
                    for (field in views().filterIsInstance<EditText>().filter { it.isShown }) field.text.clear()
                    assertFields(provider, true, "cleared")
                }
                passed.add("${labels[provider]}: provider switches, associated labels, hidden groups, empty/populated/cleared native semantics, plain URL/username/MAC, masked password only, secure window, no save/IME learning, Next focus")
                main { setup.finish() }
                runner.waitForIdleSync()
                activity = null
            }
            result.putString("checks", passed.joinToString("\n"))
            result.putString("summary", "PASS: ${passed.size} synthetic provider label checks; no imports; TalkBack speech not tested")
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("passed", passed.joinToString("\n"))
            result.putString("failure", error.stackTraceToString())
        } finally {
            runCatching { main { activity?.finish() }; runner.waitForIdleSync() }.onFailure {
                resultCode = Activity.RESULT_CANCELED
                result.putString("cleanupFailure", it.stackTraceToString())
            }
        }
        runner.finish(resultCode, result)
    }
}
