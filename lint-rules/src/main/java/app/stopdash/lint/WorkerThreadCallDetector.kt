package app.stopdash.lint

import com.android.tools.lint.client.api.JavaEvaluator
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UResolvable
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.skipParenthesizedExprDown
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.util.concurrent.ConcurrentHashMap

/**
 * Flags a call to a function marked `@WorkerThread` (or a constructor of a class so marked) from
 * composition: a composable's body or any lambda in it — `remember`, an effect, a click handler —
 * all of which run on the main thread (AGENTS.md *Main thread: read and dispatch only*). The domain
 * marks its work that grows with the data that way (matching a train to its line's routes, placing an
 * alert, grouping a board), so a screen can't call it where it freezes the UI.
 *
 * Off the main thread, and so not flagged: the block of the coroutines `withContext`, `launch` or `async`
 * handed a dispatcher other than `Dispatchers.Main` or `Dispatchers.Unconfined` (and not started
 * `UNDISPATCHED`, which runs on the caller's thread until it suspends), a lambda handed to a
 * parameter marked `@WorkerThread` (a helper that runs it on a worker), and a `Flow` operator's
 * lambda whose chain then hops to such a dispatcher with `flowOn`. The innermost hop wins.
 *
 * A function reference (`let(DepartureRows::across)`) counts as a call where it's handed over. An
 * unmarked helper is followed into, in any file: composition calling a function that runs such work
 * without a hop (directly or through further helpers) is flagged at that call. A composable's local
 * function is instead checked at each call to it.
 */
class WorkerThreadCallDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UCallExpression::class.java, UCallableReferenceExpression::class.java)

    // Per source function: the @WorkerThread work it runs on its caller's thread, if any ("" for none).
    private val reaches = ConcurrentHashMap<PsiElement, List<Work>>()

    // The @WorkerThread work a function reaches, and how many calls reach it; [viaFlow] when only inside a
    // flow it builds, which runs where that flow is collected rather than when the function is called.
    private data class Work(val name: String, val viaFlow: Boolean = false, val calls: Int = 1)

    // Each distinct work once, a run in place winning over the same work only inside a flow, counting
    // every call to it: another call behind an already baselined one changes the report, so isn't hidden.
    private fun List<Work>.distinctWork(): List<Work> =
        groupBy { it.name }.map { (_, same) -> (same.firstOrNull { !it.viaFlow } ?: same.first()).copy(calls = same.sumOf { it.calls }) }

    // How a call in a function's body runs when the function is called.
    private enum class Inline { NO, YES, IN_FLOW }

    // The project's evaluator, for the checks that run without a file's context at hand.
    @Volatile
    private var evaluator: JavaEvaluator? = null

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        init {
            evaluator = context.evaluator
        }

        // A helper call that reaches the work twice (or two works) is reported once.
        private val reported = mutableSetOf<Pair<Int, String>>()

        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve()
            // A function value invoked (`val f = RouteStops::resolve; f(stops)`) calls what it was set to.
            val invoked = method == null || method.name == "invoke"
            val referenced = if (invoked) storedReference(node) else null
            (referenced ?: method)?.let { check(it, node) }
            // A lambda a helper returns, invoked here (`deferred(xs)()`, or kept in a `val` first), runs here.
            if (invoked && referenced == null) {
                returnedBy(node)?.let { producer ->
                    for (work in lambdaWork(context, producer)) report(work, node, "${producer.name}()")
                }
            }
            // A function value handed over (`val f = RouteStops::resolve; stops.let(f)`) runs there too.
            for (argument in node.valueArguments) {
                if (invokesArgument(argument)) storedReferenceOf(argument)?.let { check(it, argument) }
            }
        }

        // A function reference (`let(DepartureRows::across)`) runs where it's handed over, like a call.
        override fun visitCallableReferenceExpression(node: UCallableReferenceExpression) {
            if (!handedToCall(node)) return
            check(node.resolve() ?: return, node)
        }

        // [function]: a method, or a local function a reference names.
        private fun check(function: PsiElement, node: UElement) {
            // An unmarked helper is followed into: calling it from composition calls the work it runs inline.
            val direct = (function as? PsiMethod)?.takeIf(::marked)?.let(::nameOf)
            // Every work a helper reaches, each its own report: a new one behind a baselined call isn't hidden.
            val works = direct?.let { listOf(Work(it)) } ?: workIn(context, function, mutableSetOf())
            for (work in works) report(work, node, if (direct == null) (function as? PsiNamedElement)?.name?.let { "$it()" } else null)
        }

        private fun report(work: Work, node: UElement, helper: String?) {
            // Work inside a flow [node] returns runs where that flow is collected: nowhere on the main
            // thread when every way on moves it off with `flowOn`; and when it's kept or handed on, no hop
            // around [node] decides where (`withContext(worker) { resolving(xs) }` only builds it).
            if (work.viaFlow && flowedOffMain(context, node)) return
            val leaves = work.viaFlow && flowLeaves(context, node)
            for (site in sites(context, node, pinnedFirst = leaves)) {
                val via = listOfNotNull(helper, site.node.takeIf { it !== node }?.let { (it as? UCallExpression)?.methodName ?: (it as? UCallableReferenceExpression)?.callableName }?.let { "$it()" })
                val through = if (via.isEmpty()) "" else via.joinToString(prefix = " (through ", postfix = ")") { "`$it`" }
                val called = if (work.calls > 1) "called ${work.calls} times" else "called"
                val message = when (site.kind) {
                    Kind.COMPOSABLE -> "`${work.name}` is marked @WorkerThread but $called on the main thread$through, in composable `${site.owner}`: " +
                        "work it out off the main thread (`withContext(worker)` in a `produceState`, or a view model)"
                    Kind.MAIN -> "`${work.name}` is marked @WorkerThread but $called on the main thread$through, in `${site.owner}`: " +
                        "call it from a worker dispatcher instead"
                }
                val at = site.node.sourcePsi?.textOffset ?: -1
                if (reported.add(at to message)) context.report(ISSUE, site.node, context.getLocation(site.node), message)
            }
        }
    }

    private enum class Kind { COMPOSABLE, MAIN }

    private class Site(val node: UElement, val owner: String, val kind: Kind)

    // Where a coroutine context sends the code it runs.
    // COLLECTED: a flow operator's lambda, run wherever the flow is collected, which no hop around where
    // it's built decides.
    private enum class Hop { OFF_MAIN, MAIN, INHERIT, COLLECTED }

    private fun marked(method: PsiMethod): Boolean =
        hasAnnotation(method, WORKER_THREAD) || (method.isConstructor && method.containingClass?.let { hasAnnotation(it, WORKER_THREAD) } == true)

    private fun nameOf(method: PsiMethod): String? =
        if (method.isConstructor) method.containingClass?.name else "${method.containingClass?.name}.${method.name}"

    // Whether [lambda] is handed to a parameter of [method] marked `@WorkerThread` that [method] is seen
    // to run only off the main thread: a helper that runs the block it's given on a worker
    // (`rememberWorked`'s `compute`). The mark alone isn't trusted (Codex, #535).
    private fun workerBlock(context: JavaContext, call: UCallExpression, method: PsiMethod, lambda: ULambdaExpression): Boolean =
        context.evaluator.computeArgumentMapping(call, method).entries.any { (arg, param) ->
            arg.skipParenthesizedExprDown() == lambda && hasAnnotation(param, WORKER_THREAD) && runsOffMain(context, method, param.name)
        }

    // Whether [method]'s source uses its parameter [name] only by calling it inside a hop off the main
    // thread (`withContext(worker) { compute() }`): never called in place, nor handed on. No source, no trust.
    private fun runsOffMain(context: JavaContext, method: PsiMethod, name: String): Boolean {
        val body = method.toUElementOfType<UMethod>()?.uastBody ?: return false
        val uses = mutableListOf<UElement>()
        body.accept(
            object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    if (node.methodIdentifier?.name == name) uses += node
                    return super.visitCallExpression(node)
                }

                override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                    // A call's own name is counted with the call.
                    if (node.identifier == name && (node.uastParent as? UCallExpression)?.methodIdentifier?.name != name) uses += node
                    return super.visitSimpleNameReferenceExpression(node)
                }
            },
        )
        return uses.isNotEmpty() && uses.all { it is UCallExpression && offMainHop(context, it) }
    }

    // Whether [node] sits directly in the block of a hop off the main thread: its innermost lambda is that
    // block. Any other lambda between them may be run later, back on the caller's thread
    // (`val run = withContext(worker) { { compute() } }; run()`), so isn't trusted (Codex, #535).
    private fun offMainHop(context: JavaContext, node: UElement): Boolean {
        var element: UElement? = node.uastParent
        while (element != null && element !is UMethod) {
            if (element is ULambdaExpression) {
                val call = element.uastParent as? UCallExpression ?: (element.uastParent?.uastParent as? UCallExpression)
                val method = call?.resolve() ?: return false
                return hop(context, call, method) == Hop.OFF_MAIN
            }
            element = element.uastParent
        }
        return false
    }

    private fun hasAnnotation(owner: PsiModifierListOwner, qualifiedName: String): Boolean =
        owner.annotations.any { it.qualifiedName == qualifiedName }

    // The @WorkerThread work unmarked source function [method] runs on its caller's thread, directly or
    // through further unmarked functions: a call in its body that no hop takes off the main thread.
    private fun workIn(context: JavaContext, function: PsiElement, stack: MutableSet<PsiElement>): List<Work> {
        val method = function as? PsiMethod
        if (method != null && (marked(method) || hasAnnotation(method, COMPOSABLE))) return emptyList()
        // A local function (UAST shows its declaration as a lambda) is followed from each call or
        // reference to it too, which may name it as a method or as the function itself.
        val local = (function as? KtNamedFunction ?: (function.toUElement()?.sourcePsi ?: function.navigationElement) as? KtNamedFunction)
            ?.takeIf { it.isLocal }
        val key = local ?: function
        reaches[key]?.let { return it }
        val body = local?.bodyExpression?.toUElement() ?: (method?.toUElement() as? UMethod)?.uastBody
            ?: ((function as? KtNamedFunction)?.toUElement() as? UMethod)?.uastBody
        if (body == null) return emptyList()
        if (stack.size > MAX_DEPTH || !stack.add(key)) return emptyList()
        val found = workInBody(context, body, stack, mutableSetOf())
        stack.remove(key)
        reaches[key] = found
        return found
    }

    // The @WorkerThread work [body] runs inline. A local function declared in it runs only where it's
    // called, so its body is followed from those calls, not scanned where it's declared.
    private fun workInBody(context: JavaContext, body: UElement, stack: MutableSet<PsiElement>, seen: MutableSet<ULambdaExpression>): List<Work> {
        val calls = mutableListOf<UElement>()
        val locals = mutableMapOf<String, ULambdaExpression>()
        body.accept(object : AbstractUastVisitor() {
            override fun visitLambdaExpression(node: ULambdaExpression): Boolean {
                val local = node.sourcePsi as? KtNamedFunction
                if (local != null) {
                    local.name?.let { locals[it] = node }
                    return true
                }
                // A lambda kept in a `val` runs where that's invoked (`val run = { … }; run()`), as a local
                // function does.
                (node.uastParent as? UVariable)?.name?.let { name ->
                    locals[name] = node
                    return true
                }
                // A lambda only returned runs nowhere yet: followed only when handed to a call.
                return node.uastParent !is UCallExpression && node.uastParent?.uastParent !is UCallExpression
            }

            override fun visitCallExpression(node: UCallExpression): Boolean {
                calls += node
                node.valueArguments.filterTo(calls) { storedReferenceOf(it) != null && invokesArgument(it) }
                return false
            }

            override fun visitCallableReferenceExpression(node: UCallableReferenceExpression): Boolean {
                if (handedToCall(node)) calls += node
                return false
            }
        })
        val works = mutableListOf<Work>()
        for (call in calls) {
            val runs = inline(context, call)
            if (runs == Inline.NO) continue
            // By the name called, as written: a function value's call reads as `invoke` otherwise.
            val name = (call.sourcePsi as? KtCallExpression)?.calleeExpression?.text ?: (call as? UCallableReferenceExpression)?.callableName
            val local = locals[name]
            val found = if (local != null) {
                // [seen] holds only the lambdas being followed, so each call to one counts, and only a
                // lambda calling itself is cut short.
                if (seen.add(local)) workInBody(context, local.body, stack, seen).also { seen.remove(local) } else emptyList()
            } else {
                val callee = (call as? UExpression)?.let(::storedReferenceOf)
                    ?: (call as? UResolvable)?.resolve()?.takeUnless { call is UCallExpression && (it as? PsiMethod)?.name == "invoke" }
                    ?: (call as? UCallExpression)?.let(::storedReference)
                    ?: continue
                if (callee is PsiMethod && marked(callee)) listOfNotNull(nameOf(callee)?.let(::Work)) else workIn(context, callee, stack)
            }
            // Work inside a flow this builds runs where that's collected, not here.
            works += if (runs == Inline.IN_FLOW) found.map { it.copy(viaFlow = true) } else found
        }
        return works.distinctWork()
    }

    // Whether [call] runs on its enclosing function's caller's thread (no hop between them takes it off),
    // or inside a flow the function builds, run where that flow is collected.
    private fun inline(context: JavaContext, call: UElement): Inline {
        if (handedOffMain(context, call)) return Inline.NO
        // Once inside a main-thread hop or a flow operator, an outer hop no longer decides the thread.
        var pinned = false
        var onMain = false
        var inFlow = false
        var element: UElement? = call.uastParent
        while (element != null && element !is UMethod && element !is UFile) {
            if (element is ULambdaExpression) {
                // A local function's body runs where it's called, not where it's declared.
                if (element.sourcePsi is KtNamedFunction) break
                val outer = element.uastParent as? UCallExpression ?: (element.uastParent?.uastParent as? UCallExpression)
                val method = outer?.resolve()
                // As in composition: a block handed to a helper that runs it on a worker is off the main
                // thread, here in a helper's own body too (a store's `update { … }`, Codex #642).
                val hopped = when {
                    outer == null || method == null -> Hop.INHERIT
                    workerBlock(context, outer, method, element) -> Hop.OFF_MAIN
                    else -> hop(context, outer, method)
                }
                when (hopped) {
                    Hop.OFF_MAIN -> if (!pinned) return Inline.NO
                    Hop.MAIN -> {
                        pinned = true
                        onMain = true
                    }
                    Hop.COLLECTED -> {
                        pinned = true
                        inFlow = true
                    }
                    Hop.INHERIT -> {}
                }
            }
            element = element.uastParent
        }
        return if (inFlow && !onMain) Inline.IN_FLOW else Inline.YES
    }

    // Whether the flow [call] returns is collected only off the main thread: every way it goes on ends in
    // a worker `flowOn`.
    private fun flowedOffMain(context: JavaContext, call: UElement): Boolean = flowHops(context, call).all { it == Hop.OFF_MAIN }

    // Whether the flow [call] returns may be collected somewhere no hop around [call] decides: it's kept,
    // returned or handed on (`val pending = withContext(worker) { resolving(xs) }; pending.collect()`), or
    // hops onto the main thread, rather than collected in place or moved off it with `flowOn`.
    private fun flowLeaves(context: JavaContext, call: UElement): Boolean =
        flowHops(context, call).any { it == Hop.COLLECTED || it == Hop.MAIN }

    // Where worker-thread work at [node] runs on the main thread: [node] in a composable, or under a hop
    // onto the main dispatcher. Inside a local function, nowhere here: it's reported at each call to the
    // function, as a helper's is.
    private fun sites(context: JavaContext, node: UElement, pinnedFirst: Boolean = false): List<Site> {
        if (handedOffMain(context, node)) return emptyList()
        var onMain = false
        // Once inside a main-thread hop or a flow operator, an outer hop no longer decides the thread.
        var pinned = pinnedFirst
        var element: UElement? = node.uastParent
        while (element != null && element !is UFile) {
            if (element is ULambdaExpression) {
                // A local function (UAST shows it as a lambda) runs where it's called, not where it's
                // declared: a composable's helper is often called only inside its `withContext`.
                if (element.sourcePsi is KtNamedFunction) return emptyList()
                val call = element.uastParent as? UCallExpression
                    ?: (element.uastParent?.uastParent as? UCallExpression)
                val method = call?.resolve()
                // A lambda handed to no call is kept to run later (`val run = { work() }`, or one a hop's
                // block returns): wherever that is, no hop around where it's built decides (Codex, #535).
                if (call == null) pinned = true
                // The innermost hop wins: a `withContext(Main)` inside a worker block is the main thread.
                val hopped = when {
                    call == null || method == null -> Hop.INHERIT
                    workerBlock(context, call, method, element) -> Hop.OFF_MAIN
                    else -> hop(context, call, method)
                }
                when (hopped) {
                    Hop.OFF_MAIN -> if (!pinned) return emptyList()
                    Hop.MAIN -> {
                        onMain = true
                        pinned = true
                    }
                    Hop.COLLECTED -> pinned = true
                    Hop.INHERIT -> {}
                }
                // A lambda typed `@Composable` (`val Stops: @Composable () -> Unit = { … }`) is composition.
                composableLambda(element)?.let { return listOf(Site(node, it, Kind.COMPOSABLE)) }
            }
            if (element is UMethod) {
                return when {
                    hasAnnotation(element, COMPOSABLE) -> listOf(Site(node, element.name, Kind.COMPOSABLE))
                    onMain -> listOf(Site(node, element.name, Kind.MAIN))
                    else -> emptyList()
                }
            }
            element = element.uastParent
        }
        return emptyList()
    }

    // The function a plain `val` invoked at [call] was set to by reference (`val f = ::work`), if any.
    private fun storedReference(call: UCallExpression): PsiElement? =
        ((call.sourcePsi as? KtCallExpression)?.calleeExpression?.toUElement() as? UExpression)?.let(::storedReferenceOf)

    // The function whose returned value [call] invokes: `deferred(xs)()`, or `val f = deferred(xs); f()`.
    private fun returnedBy(call: UCallExpression): PsiMethod? {
        val callee = (call.sourcePsi as? KtCallExpression)?.calleeExpression ?: return null
        val producer = (callee as? KtCallExpression)?.toUElementOfType<UCallExpression>()
            ?: ((callee.toUElement() as? UResolvable)?.resolve()?.toUElement() as? UVariable)?.uastInitializer?.skipParenthesizedExprDown() as? UCallExpression
        return producer?.resolve()
    }

    // The @WorkerThread work the lambda [method] returns runs when it's invoked, if any.
    private fun lambdaWork(context: JavaContext, method: PsiMethod): List<Work> {
        val body = (method.toUElement() as? UMethod)?.uastBody ?: return emptyList()
        val returned = mutableListOf<ULambdaExpression>()
        body.accept(object : AbstractUastVisitor() {
            override fun visitReturnExpression(node: UReturnExpression): Boolean {
                (node.returnExpression?.skipParenthesizedExprDown() as? ULambdaExpression)?.let { returned += it }
                return false
            }
        })
        // An expression body (`fun deferred(xs) = { … }`) returns its lambda too.
        (body.skipParenthesizedExprDown() as? ULambdaExpression)?.let { returned += it }
        return returned.flatMap { workInBody(context, it.body, mutableSetOf(), mutableSetOf()) }.distinctWork()
    }

    // The function [expression], naming a plain `val`, was set to by reference, if any.
    private fun storedReferenceOf(expression: UExpression): PsiElement? {
        val name = expression.skipParenthesizedExprDown() as? USimpleNameReferenceExpression ?: return null
        val variable = name.resolve()?.toUElement() as? UVariable ?: return null
        return (variable.uastInitializer?.skipParenthesizedExprDown() as? UCallableReferenceExpression)?.resolve()
    }

    // Whether function reference [node] is handed to a call, which runs it; one only stored (`val f =
    // ::work`) runs nowhere yet.
    private fun handedToCall(node: UCallableReferenceExpression): Boolean = invokesArgument(node)

    // Whether the call [argument] is handed to takes it as a function, and so runs it: a `let` or `map`
    // does, a `keep(value: T)` only holds it.
    private fun invokesArgument(argument: UExpression): Boolean {
        val call = argument.uastParent as? UCallExpression ?: return false
        val method = call.resolve() ?: return true
        val parameter = evaluator?.computeArgumentMapping(call, method)?.get(argument) ?: return true
        val type = parameter.type.canonicalText
        return type.startsWith("kotlin.jvm.functions.Function") || type.startsWith("kotlin.Function") ||
            type.startsWith("kotlin.coroutines.SuspendFunction") || type.startsWith("kotlin.reflect.KFunction")
    }

    // The name of [lambda] when it's declared `@Composable` by its type, or null.
    private fun composableLambda(lambda: ULambdaExpression): String? {
        val declaration = (lambda.sourcePsi as? KtLambdaExpression)?.parent as? KtCallableDeclaration
        if (declaration != null) return declaration.name?.takeIf { declaration.typeReference?.text?.contains("@Composable") == true }
        // Handed to a parameter typed `@Composable` (Glance's `provideContent { … }`).
        val call = lambda.uastParent as? UCallExpression ?: lambda.uastParent?.uastParent as? UCallExpression ?: return null
        val method = call.resolve() ?: return null
        // A compiled host's `@Composable` parameter type isn't visible here: the known ones by name.
        val owner = method.containingClass?.qualifiedName.orEmpty()
        if (method.name in COMPOSABLE_HOSTS && owner.startsWith("androidx.")) return "${method.name} { }"
        val argument = call.valueArguments.firstOrNull { it === lambda || it.skipParenthesizedExprDown() === lambda } ?: lambda
        val parameter = evaluator?.computeArgumentMapping(call, method)?.get(argument) ?: return null
        val composable = parameter.type.annotations.any { it.qualifiedName == COMPOSABLE } ||
            ((parameter as? PsiElement)?.navigationElement as? KtParameter)?.typeReference?.text?.contains("@Composable") == true
        return if (composable) "${method.name} { }" else null
    }

    // Whether function reference [node] is handed straight to a hop off the main thread
    // (`withContext(worker, ::work)`), and so runs there.
    private fun handedOffMain(context: JavaContext, node: UElement): Boolean {
        // A function value handed over: a reference, or a `val` holding one (`flow.map(resolve)`).
        if (node !is UCallableReferenceExpression && (node as? UExpression)?.let(::storedReferenceOf) == null) return false
        val outer = node.uastParent as? UCallExpression ?: return false
        val method = outer.resolve() ?: return false
        return hop(context, outer, method) == Hop.OFF_MAIN
    }

    // Where [call] runs its lambda.
    private fun hop(context: JavaContext, call: UCallExpression, method: PsiMethod): Hop {
        val owner = method.containingClass?.qualifiedName.orEmpty()
        if (owner.startsWith("kotlinx.coroutines.flow.")) {
            // A flow operator's lambda runs where the flow is collected, unless the chain hops on.
            // A terminal call (`collect`, `first`) runs its lambda right where it's called.
            val builds = (call.getExpressionType() as? PsiClassType)?.resolve()?.let { context.evaluator.inheritsFrom(it, FLOW, false) } == true
            if (!builds) return Hop.INHERIT
            // Off the main thread only when every way the flow is collected hops there with `flowOn`; where
            // it's collected in place, the hops around that collection decide.
            val hops = flowHops(context, call)
            return when {
                hops.all { it == Hop.OFF_MAIN } -> Hop.OFF_MAIN
                Hop.MAIN in hops -> Hop.MAIN
                Hop.COLLECTED in hops -> Hop.COLLECTED
                else -> Hop.INHERIT
            }
        }
        if (!owner.startsWith("kotlinx.coroutines.") || method.name !in HOPS) return Hop.INHERIT
        val args = context.evaluator.computeArgumentMapping(call, method).entries.associate { it.value.name to it.key }
        // An undispatched start runs the block on the caller's thread up to its first suspension.
        if (args["start"]?.let { undispatched(it, depth = 0) } == true) return Hop.INHERIT
        return dispatch(context, args["context"] ?: return Hop.INHERIT, depth = 0)
    }

    // Whether coroutine start [expression] may be `UNDISPATCHED`: anything but a start shown to dispatch
    // (`DEFAULT`, `LAZY`, `ATOMIC`), a plain `val` followed to its value.
    private fun undispatched(expression: UExpression, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        val expr = expression.skipParenthesizedExprDown()
        val text = expr.sourcePsi?.text.orEmpty()
        if ("UNDISPATCHED" in text) return true
        if (DISPATCHED_STARTS.any { text.endsWith(it) }) return false
        val variable = (expr as? UResolvable)?.resolve()?.toUElement() as? UVariable
        // A `var` may since have been set to anything.
        if (variable != null && mutable(variable)) return true
        val initializer = variable?.uastInitializer
        return initializer == null || initializer === expr || undispatched(initializer, depth + 1)
    }

    // For each way [start]'s chain goes on, where its operators run: where a later `flowOn` sends them,
    // INHERIT for one collected in the same chain first (`flow.map { … }.toList()`, run where that
    // collection runs), or COLLECTED for one that leaves the chain uncollected; a chain split across a
    // local `val` goes on at each of its uses (`val mapped = flow.map { … }; mapped.flowOn(worker)`).
    private fun flowHops(context: JavaContext, start: UElement, depth: Int = 0): List<Hop> {
        var element: UElement = start
        while (true) {
            val chain = element.uastParent as? UQualifiedReferenceExpression
            if (chain == null) {
                val variable = element.uastParent as? UVariable ?: return listOf(Hop.COLLECTED)
                if (depth > MAX_DEPTH) return listOf(Hop.COLLECTED)
                val scope = variable.getParentOfType<UMethod>() ?: return listOf(Hop.COLLECTED)
                val uses = mutableListOf<USimpleNameReferenceExpression>()
                scope.accept(object : AbstractUastVisitor() {
                    override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                        if (node.resolve()?.toUElement()?.sourcePsi == variable.sourcePsi) uses += node
                        return false
                    }
                })
                return uses.flatMap { flowHops(context, it, depth + 1) }.ifEmpty { listOf(Hop.COLLECTED) }
            }
            val next = chain.selector as? UCallExpression
            if (next != null && next !== element) {
                if (next.methodName == "flowOn") return listOf(next.valueArguments.firstOrNull()?.let { dispatch(context, it, depth = 0) } ?: Hop.MAIN)
                if (collectsInPlace(context, next)) return listOf(Hop.INHERIT)
            }
            element = chain
        }
    }

    // Whether [call] collects a flow right where it's called (`collect`, `toList`, `first`), rather than
    // building another or collecting in a scope of its own (`launchIn`).
    private fun collectsInPlace(context: JavaContext, call: UCallExpression): Boolean {
        val method = call.resolve() ?: return false
        if (method.containingClass?.qualifiedName?.startsWith("kotlinx.coroutines.flow.") != true || method.name == "launchIn") return false
        return (call.getExpressionType() as? PsiClassType)?.resolve()?.let { context.evaluator.inheritsFrom(it, FLOW, false) } != true
    }

    // Whether [variable] is a `var`, which may since have been set to something else.
    private fun mutable(variable: UVariable): Boolean = (variable.sourcePsi as? KtProperty)?.isVar == true

    // Where coroutine context [expression] dispatches. Off the main thread only when it is, or holds
    // (`+`), a dispatcher other than `Dispatchers.Main` or `Dispatchers.Unconfined`; a context holding no
    // dispatcher, such as `NonCancellable`, inherits the caller's; anything not known is the main thread.
    private fun dispatch(context: JavaContext, expression: UExpression, depth: Int): Hop {
        if (depth > MAX_DEPTH) return Hop.MAIN
        val expr = expression.skipParenthesizedExprDown()
        // Combined, the right side's dispatcher replaces the left's; a side with none leaves the other's.
        if (expr is UBinaryExpression && expr.operator == UastBinaryOperator.PLUS) {
            return dispatch(context, expr.rightOperand, depth + 1).takeIf { it != Hop.INHERIT }
                ?: dispatch(context, expr.leftOperand, depth + 1)
        }
        if (STAYS_ON_MAIN.any { it in expr.sourcePsi?.text.orEmpty() }) return Hop.MAIN
        // Follow a plain `val` to what it was set to, so `val d = Dispatchers.Main` is still the main thread.
        // A local `var` may since have been set to anything; a mutable property, like a parameter, is
        // trusted to hold what its type says (an injected worker).
        val variable = (expr as? UResolvable)?.resolve()?.toUElement() as? UVariable
        if (variable is ULocalVariable && mutable(variable)) return Hop.MAIN
        val initializer = variable?.takeUnless(::mutable)?.uastInitializer
        if (initializer != null && initializer !== expr) return dispatch(context, initializer, depth + 1)
        val type = (expr.getExpressionType() as? PsiClassType)?.resolve() ?: return Hop.MAIN
        return when {
            context.evaluator.inheritsFrom(type, MAIN_DISPATCHER, false) -> Hop.MAIN
            context.evaluator.inheritsFrom(type, DISPATCHER, false) -> Hop.OFF_MAIN
            // A context element that isn't a dispatcher (a job, a name, an exception handler) leaves the
            // caller's in place.
            context.evaluator.inheritsFrom(type, JOB, false) || NO_DISPATCHER.any { context.evaluator.inheritsFrom(type, it, false) } -> Hop.INHERIT
            context.evaluator.inheritsFrom(type, CONTEXT_ELEMENT, false) && !context.evaluator.inheritsFrom(type, INTERCEPTOR, false) -> Hop.INHERIT
            else -> Hop.MAIN
        }
    }

    companion object {
        private const val COMPOSABLE = "androidx.compose.runtime.Composable"
        private const val WORKER_THREAD = "androidx.annotation.WorkerThread"

        private const val DISPATCHER = "kotlinx.coroutines.CoroutineDispatcher"
        private const val MAIN_DISPATCHER = "kotlinx.coroutines.MainCoroutineDispatcher"
        private const val JOB = "kotlinx.coroutines.Job"
        private const val CONTEXT_ELEMENT = "kotlin.coroutines.CoroutineContext.Element"
        private const val INTERCEPTOR = "kotlin.coroutines.ContinuationInterceptor"
        private val NO_DISPATCHER = listOf("kotlinx.coroutines.CoroutineName", "kotlinx.coroutines.CoroutineExceptionHandler")
        private const val FLOW = "kotlinx.coroutines.flow.Flow"
        // Library calls whose lambda is composition: Glance's widget content, an activity's or view's.
        private val COMPOSABLE_HOSTS = setOf("provideContent", "setContent")
        private val DISPATCHED_STARTS = listOf("CoroutineStart.DEFAULT", "CoroutineStart.LAZY", "CoroutineStart.ATOMIC")
        private const val MAX_DEPTH = 8

        // The coroutines calls that run their block on the context they're handed.
        private val HOPS = setOf("withContext", "launch", "async")

        // A context handed one of these runs its block on the main thread.
        private val STAYS_ON_MAIN = listOf("Dispatchers.Main", "Dispatchers.Unconfined", "Main.immediate")

        @JvmField
        val ISSUE: Issue = Issue.create(
            id = "WorkerThreadCall",
            briefDescription = "Worker-thread work called from composition",
            explanation = """
                A function marked @WorkerThread does work that grows with the data — matching a \
                train to its line's routes, placing an alert, grouping a board — and froze the app \
                when composition called it (AGENTS.md, Main thread: read and dispatch only). Call it \
                off the main thread: in `withContext(worker)` inside a `produceState`, or in a view \
                model that publishes the result.
            """,
            category = Category.PERFORMANCE,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(WorkerThreadCallDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
