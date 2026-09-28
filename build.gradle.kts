// Top-level build file. Declares plugin versions from the version catalog
// so submodules can apply them without re-specifying versions.
// No configuration here is applied to any module directly.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
