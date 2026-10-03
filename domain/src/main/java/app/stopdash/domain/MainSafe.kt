package app.stopdash.domain

/**
 * Says a function or class of this module does constant work, however big its input — a lookup, a
 * field read, a small fixed formatting — so a composable may call it on the main thread. Every other
 * call into this module from composition, a click handler or an effect body is a lint error
 * (`MainThreadWork`, AGENTS.md *Main thread: read and dispatch only*): a new function is off the
 * main thread until someone says it's safe on it, rather than safe until a bug report says it isn't.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
annotation class MainSafe
