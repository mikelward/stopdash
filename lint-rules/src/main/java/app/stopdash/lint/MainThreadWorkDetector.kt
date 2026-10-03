package app.stopdash.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getParentOfType

/**
 * Flags work that grows with its input where it runs on the main thread (AGENTS.md *Main thread:
 * read and dispatch only*): a collection derived, a regular expression, or a call into the domain
 * module that isn't marked `@MainSafe`, made from
 *  - a composable's body, or any lambda in it — `remember`, an effect, a click handler — all of which
 *    run on the main thread,
 *  - a plain function in the UI package (`app.stopdash.ui`), which composition calls, or
 *  - a `suspend` function in the UI or domain package, before it hops: suspending doesn't change
 *    threads, so its body runs on its caller's until a `withContext`.
 *
 * Off the main thread, and so not flagged: the work lambda of `rememberComputed` or `withContext`, a
 * lambda handed to a `Flow` operator whose chain then hops with `flowOn` (without one it runs where
 * the flow is collected, which may be the main thread), and a function marked `@WorkerThread`, which
 * composition may not call either. A call to a domain `suspend` function is
 * fine: this check holds its body to hopping first.
 *
 * A `for` loop and `forEach` aren't flagged: in composition they lay out one item each, which is
 * rendering, not deriving. A call to a collection function with no lambda is flagged only where it
 * copies or walks the whole input (`sorted`, `distinct`, `joinToString`...); `first()` isn't.
 */
class MainThreadWorkDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve() ?: return
            val what = workOf(context, node, method) ?: return
            val where = mainThreadContext(context, node) ?: return
            context.report(
                ISSUE,
                node,
                context.getLocation(node),
                "$what on the main thread, in $where: move it into `rememberComputed { }`, a `suspend` " +
                    "function that hops to `Workers.compute`, or a `@WorkerThread` function called from one",
            )
        }
    }

    // What makes [method] input-sized work, said for the message; null when it isn't.
    private fun workOf(context: JavaContext, node: UCallExpression, method: PsiMethod): String? {
        val owner = method.containingClass?.qualifiedName.orEmpty()
        val name = method.name
        if (hasAnnotation(method, WORKER_THREAD)) return "`$name`, marked @WorkerThread,"
        if (owner.startsWith("kotlin.collections.") || owner.startsWith("kotlin.sequences.")) {
            val withLambda = node.valueArguments.any { it is ULambdaExpression } ||
                method.parameterList.parameters.any { it.type.canonicalText.startsWith("kotlin.jvm.functions.Function") }
            if (name in DERIVING_WITH_LAMBDA && withLambda) return "Deriving a collection (`$name`)"
            if (name in DERIVING_WHOLE) return "Deriving a collection (`$name`)"
            return null
        }
        if (owner == "kotlin.text.Regex" || owner == "java.util.regex.Pattern" || name == "toRegex") return "A regular expression (`$name`)"
        if (owner.startsWith("kotlin.text.") && method.parameterList.parameters.any { it.type.canonicalText == "kotlin.text.Regex" }) {
            return "A regular expression (`$name`)"
        }
        if (owner.startsWith(DOMAIN) && !method.isConstructor && name !in DATA_CLASS_MEMBERS && !name.matches(COMPONENT) &&
            !context.evaluator.isSuspend(method) && !mainSafe(method)
        ) {
            return "`${method.containingClass?.name}.$name`, not marked @MainSafe,"
        }
        return null
    }

    private fun mainSafe(method: PsiMethod): Boolean {
        if (hasAnnotation(method, MAIN_SAFE)) return true
        var cls: PsiClass? = method.containingClass
        while (cls != null) {
            if (hasAnnotation(cls, MAIN_SAFE)) return true
            cls = cls.containingClass
        }
        return false
    }

    private fun hasAnnotation(owner: PsiModifierListOwner, qualifiedName: String): Boolean =
        owner.annotations.any { it.qualifiedName == qualifiedName }

    // Where [node] runs on the main thread, said for the message; null when it runs off it.
    private fun mainThreadContext(context: JavaContext, node: UCallExpression): String? {
        var element: UElement? = node.uastParent
        while (element != null && element !is UFile) {
            if (element is ULambdaExpression) {
                val call = element.uastParent as? UCallExpression
                    ?: (element.uastParent?.uastParent as? UCallExpression)
                val name = call?.methodName
                if (name in OFF_MAIN_LAMBDAS) return null
                val owner = call?.resolve()?.containingClass?.qualifiedName.orEmpty()
                // A flow operator's lambda runs where the flow is collected, so only one whose chain
                // hops further on (`.flowOn(…)`) is off the main thread.
                if (call != null && owner.startsWith("kotlinx.coroutines.flow.") && hopsDownstream(call)) return null
            }
            if (element is UMethod) {
                if (hasAnnotation(element, COMPOSABLE)) return "composable `${element.name}`"
                // A local function inside a composable runs when composition calls it.
                val outer = element.uastParent?.getParentOfType<UMethod>(strict = false)
                if (outer != null && hasAnnotation(outer, COMPOSABLE)) return "composable `${outer.name}`"
                if (hasAnnotation(element, WORKER_THREAD)) return null
                val cls = element.getContainingUClass()
                if (cls != null && hasAnnotation(cls, WORKER_THREAD)) return null
                val pkg = context.uastFile?.packageName.orEmpty()
                val ui = pkg == UI || pkg.startsWith("$UI.")
                if (context.evaluator.isSuspend(element)) {
                    val domain = pkg == DOMAIN.removeSuffix(".") || pkg.startsWith(DOMAIN)
                    return if (ui || domain) "suspend function `${element.name}` before it hops" else null
                }
                return if (ui) "UI function `${element.name}`" else null
            }
            element = element.uastParent
        }
        return null
    }

    // Whether a call later in [call]'s chain moves the flow upstream of it to another dispatcher.
    private fun hopsDownstream(call: UCallExpression): Boolean {
        var element: UElement = call
        while (true) {
            val chain = element.uastParent as? UQualifiedReferenceExpression ?: return false
            val next = chain.selector as? UCallExpression
            if (next != null && next !== element && next.methodName == "flowOn") return true
            element = chain
        }
    }

    companion object {
        private const val COMPOSABLE = "androidx.compose.runtime.Composable"
        private const val WORKER_THREAD = "androidx.annotation.WorkerThread"
        private const val MAIN_SAFE = "app.stopdash.domain.MainSafe"
        private const val DOMAIN = "app.stopdash.domain."
        private const val UI = "app.stopdash.ui"

        private val OFF_MAIN_LAMBDAS = setOf("rememberComputed", "withContext")

        private val DATA_CLASS_MEMBERS = setOf("copy", "equals", "hashCode", "toString", "values", "valueOf", "getEntries")
        private val COMPONENT = Regex("component\\d+")

        // Collection functions that walk their input only when handed a lambda: a predicate or a
        // transform. Without one (`first()`, `take(3)`) they read a fixed few.
        private val DERIVING_WITH_LAMBDA = setOf(
            "filter", "filterNot", "filterTo", "filterNotTo", "filterIndexed", "filterKeys", "filterValues",
            "map", "mapNotNull", "mapIndexed", "mapIndexedNotNull", "mapTo", "mapNotNullTo", "mapKeys", "mapValues",
            "flatMap", "flatMapTo", "associate", "associateBy", "associateWith", "associateTo", "associateByTo",
            "groupBy", "groupByTo", "groupingBy", "partition", "sortedBy", "sortedByDescending", "sortedWith",
            "distinctBy", "fold", "foldIndexed", "reduce", "sumOf", "maxBy", "maxByOrNull", "minBy", "minByOrNull",
            "maxOf", "minOf", "maxOfOrNull", "minOfOrNull", "count", "any", "all", "none", "first", "firstOrNull",
            "last", "lastOrNull", "find", "findLast", "single", "singleOrNull", "indexOfFirst", "indexOfLast",
            "firstNotNullOf", "firstNotNullOfOrNull", "takeWhile", "dropWhile", "onEach", "zip", "windowed",
            "chunked", "joinToString", "toSortedMap",
        )

        // Collection functions that copy or walk their whole input with or without a lambda.
        private val DERIVING_WHOLE = setOf(
            "sorted", "sortedDescending", "distinct", "reversed", "flatten", "filterNotNull", "filterIsInstance",
            "toSet", "toHashSet", "toMutableSet", "toList", "toMutableList", "toMap", "toMutableMap",
            "joinToString", "sum", "average", "union", "intersect", "subtract", "zip", "chunked", "windowed",
            "plus", "minus", "shuffled",
        )

        @JvmField
        val ISSUE: Issue = Issue.create(
            id = "MainThreadWork",
            briefDescription = "Input-sized work on the main thread",
            explanation = """
                The main thread only reads state that is already worked out and hands work off \
                (AGENTS.md, Main thread: read and dispatch only). Deriving a collection, a regular \
                expression, or a domain call not marked @MainSafe, in composition, an effect, a click \
                handler or a UI helper, grows with the data, and on a long route it froze the app. \
                Work it out in `rememberComputed { }` or a `suspend` function that hops to \
                `Workers.compute` first.
            """,
            category = Category.PERFORMANCE,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(MainThreadWorkDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
