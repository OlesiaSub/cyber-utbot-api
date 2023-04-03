package org.cyber.utbot.api.utils.overrides

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.cyber.utbot.api.utils.additions.pathSelector.CyberSelector
import org.cyber.utbot.api.utils.additions.pathSelector.cyberPathSelector
import org.cyber.utbot.api.utils.additions.wrappers.CookieWrapper
import org.cyber.utbot.api.utils.additions.wrappers.HttpServletResponseWrapper
import org.cyber.utbot.api.utils.additions.wrappers.HttpServletWrapper
import org.cyber.utbot.api.utils.additions.wrappers.PathWrapper
import org.cyber.utbot.api.utils.annotations.CyberModify
import org.cyber.utbot.api.utils.annotations.CyberNew
import org.cyber.utbot.api.utils.viewers.StatePublisher
import org.cyber.utbot.api.utils.vulnerability.VulnerabilityChecksHolder
import org.utbot.analytics.Predictors
import org.utbot.common.bracket
import org.utbot.common.debug
import org.utbot.engine.*
import org.utbot.engine.pc.UtSolver
import org.utbot.engine.pc.UtSolverStatusSAT
import org.utbot.engine.selectors.PathSelector
import org.utbot.engine.selectors.StrategyOption
import org.utbot.engine.selectors.nurs.NonUniformRandomSearch
import org.utbot.engine.selectors.pollUntilFastSAT
import org.utbot.engine.selectors.strategies.GraphViz
import org.utbot.engine.state.ExecutionStackElement
import org.utbot.engine.state.ExecutionState
import org.utbot.engine.state.StateLabel
import org.utbot.engine.symbolic.SymbolicState
import org.utbot.engine.util.mockListeners.MockListenerController
import org.utbot.framework.UtSettings
import org.utbot.framework.UtSettings.processUnknownStatesDuringConcreteExecution
import org.utbot.framework.UtSettings.useDebugVisualization
import org.utbot.framework.plugin.api.*
import org.utbot.framework.plugin.api.util.UtContext
import org.utbot.framework.plugin.api.util.description
import org.utbot.framework.util.classesToLoad
import soot.Scene
import java.nio.file.Path
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletResponse
import kotlin.system.measureTimeMillis


class CyberUtBotSymbolicEngine(
    controller: EngineController,
    methodUnderTest: ExecutableId,
    classpath: String,
    dependencyPaths: String,
    mockStrategy: MockStrategy = MockStrategy.OTHER_PACKAGES,
    chosenClassesToMockAlways: Set<ClassId>,
    solverTimeoutInMillis: Int = UtSettings.checkSolverTimeoutMillis,
    cyberPathSelector: Boolean = false,
    findVulnerabilities: Boolean = true,
    private var onlyVulnerabilities: Boolean = true,
    private val statePublisher: StatePublisher = StatePublisher(),
    vulnerabilityChecksHolder: VulnerabilityChecksHolder?,
    analysedJar: String,
    cyberDefaultSelector: Boolean,
) : UtBotSymbolicEngine(controller, methodUnderTest, classpath, dependencyPaths, mockStrategy, chosenClassesToMockAlways, solverTimeoutInMillis) {

    override lateinit var pathSelector: PathSelector

    init {  // set our selector
        pathSelector = if (cyberPathSelector) {
            cyberPathSelector(globalGraph, StrategyOption.DISTANCE, analysedJar, cyberDefaultSelector) {
                withStepsLimit(UtSettings.pathSelectorStepsLimit)
            }
        } else {
            pathSelector(globalGraph, typeRegistry)
        }
        if (findVulnerabilities) {
            mocker = CyberMocker(
                mockStrategy,
                classUnderTest,
                hierarchy,
                chosenClassesToMockAlways,
                MockListenerController(controller)
            )
            traverser = CyberTraverser(
                methodUnderTest,
                typeRegistry,
                hierarchy,
                typeResolver,
                globalGraph,
                mocker,
                vulnerabilityChecksHolder = vulnerabilityChecksHolder
            )

            // for overrides
            classesToLoad = classesToLoad.plus(arrayOf(
                // add all overrides here!!!
                org.cyber.utils.overrides.CyberPath::class,
                org.cyber.utils.overrides.CyberCookie::class,
                org.cyber.utils.overrides.CyberHttpServlet::class,
                org.cyber.utils.overrides.CyberHttpServletResponse::class,
            ).map { it.java }.toTypedArray())

            classToWrapper += (mutableMapOf<TypeToBeWrapped, WrapperType>().apply {
                // add all overrides here!!!
                putSootClass(java.nio.file.Path::class, org.cyber.utils.overrides.CyberPath::class)
                putSootClass(HttpServletResponse::class, org.cyber.utils.overrides.CyberHttpServletResponse::class)
                putSootClass(Cookie::class, org.cyber.utils.overrides.CyberCookie::class)
                putSootClass(HttpServlet::class, org.cyber.utils.overrides.CyberHttpServlet::class)
            }.apply {
                val applicationClassLoader = UtContext::class.java.classLoader
                values.distinct().forEach {
                    val kClass = applicationClassLoader.loadClass(it.className).kotlin
                    putSootClass(kClass, it)
                }
            })

            wrappers = wrappers + mutableMapOf(
                // add all overrides here!!!
                wrap(java.nio.file.Path::class) { type, addr -> objectValue(type, addr, PathWrapper()) },
                wrap(HttpServletResponse::class) { type, addr -> objectValue(type, addr, HttpServletResponseWrapper()) },
                wrap(Cookie::class) { type, addr -> objectValue(type, addr, CookieWrapper()) },
                wrap(HttpServlet::class) { type, addr -> objectValue(type, addr, HttpServletWrapper()) },
            ).apply {
                arrayOf(
                    // add all overrides here!!!
                    wrap(org.cyber.utils.overrides.CyberPath::class) { _, addr -> objectValue(Scene.v().getSootClass(Path::class.java.canonicalName).type, addr, PathWrapper()) },
                    wrap(org.cyber.utils.overrides.CyberHttpServletResponse::class) { _, addr -> objectValue(Scene.v().getSootClass(HttpServletResponse::class.java.canonicalName).type, addr, HttpServletResponseWrapper()) },
                    wrap(org.cyber.utils.overrides.CyberCookie::class) { _, addr -> objectValue(Scene.v().getSootClass(Cookie::class.java.canonicalName).type, addr, CookieWrapper()) },
                    wrap(org.cyber.utils.overrides.CyberHttpServlet::class) { _, addr -> objectValue(Scene.v().getSootClass(HttpServlet::class.java.canonicalName).type, addr, HttpServletWrapper()) },
                ).let { putAll(it) }
            }.also {
                val missedWrappers = it.keys.filterNot { key ->
                    Scene.v().getSootClass(key.name).type in classToWrapper.keys
                }

                require(missedWrappers.isEmpty()) {
                    "Missed wrappers for classes [${missedWrappers.joinToString(", ")}]"
                }
            }
        } else {
            if (onlyVulnerabilities) {
                onlyVulnerabilities = false
                logger.warn { "ignore onlyVulnerabilities because findVulnerabilities is false" }
            }
        }
    }

    @CyberModify("org/utbot/engine/UtBotSymbolicEngine.kt", "add StateViewer")
    override fun traverseImpl(): Flow<UtResult> = flow {

        require(trackableResources.isEmpty())

        if (useDebugVisualization) GraphViz(globalGraph, pathSelector)

        val initStmt = graph.head
        val initState = ExecutionState(
            initStmt,
            SymbolicState(UtSolver(typeRegistry, trackableResources, solverTimeoutInMillis)),
            executionStack = persistentListOf(ExecutionStackElement(null, method = graph.body.method))
        )

        pathSelector.offer(initState)

        @CyberNew("inform selector about the start of a new selection iteration & add taint endpoints")
        if (pathSelector is CyberSelector && traverser is CyberTraverser) {
            (traverser as CyberTraverser).taintEndPoints.clear()
            (traverser as CyberTraverser).taintEndPoints.putAll((pathSelector as CyberSelector).onNextIteration(initState).endPointToSinks)
        }

        pathSelector.use {
            while (currentCoroutineContext().isActive) {
                if (controller.stop)
                    break

                if (controller.paused) {
                    try {
                        yield()
                    } catch (e: CancellationException) { //todo in future we should just throw cancellation
                        break
                    }
                    continue
                }

                stateSelectedCount++
                pathLogger.trace {
                    "traverse<$methodUnderTest>: choosing next state($stateSelectedCount), " +
                            "queue size=${(pathSelector as? NonUniformRandomSearch)?.size ?: -1}"
                }

                if (controller.executeConcretely || statesForConcreteExecution.isNotEmpty()) {
                    val state = pathSelector.pollUntilFastSAT()
                        ?: statesForConcreteExecution.pollUntilSat(processUnknownStatesDuringConcreteExecution)
                        ?: break
                    // This state can contain inconsistent wrappers - for example, Map with keys but missing values.
                    // We cannot use withWrapperConsistencyChecks here because it needs solver to work.
                    // So, we have to process such cases accurately in wrappers resolving.

                    logger.trace { "executing $state concretely..." }


                    logger.debug().bracket("concolicStrategy<$methodUnderTest>: execute concretely") {
                        val resolver = Resolver(
                            hierarchy,
                            state.memory,
                            typeRegistry,
                            typeResolver,
                            state.solver.lastStatus as UtSolverStatusSAT,
                            methodUnderTest,
                            softMaxArraySize,
                            traverser.objectCounter
                        )

                        val resolvedParameters = state.methodUnderTestParameters
                        val (modelsBefore, _, instrumentation) = resolver.resolveModels(resolvedParameters)
                        val stateBefore = modelsBefore.constructStateForMethod(methodUnderTest)

                        try {
                            val concreteExecutionResult =
                                concreteExecutor.executeConcretely(methodUnderTest, stateBefore, instrumentation)

                            if (concreteExecutionResult.violatesUtMockAssumption()) {
                                logger.debug { "Generated test case violates the UtMock assumption: $concreteExecutionResult" }
                                return@bracket
                            }

                            val concreteUtExecution = UtSymbolicExecution(
                                stateBefore,
                                concreteExecutionResult.stateAfter,
                                concreteExecutionResult.result,
                                instrumentation,
                                mutableListOf(),
                                listOf(),
                                concreteExecutionResult.coverage
                            )
                            @CyberModify("filter emit") if (!onlyVulnerabilities) {
                                emit(concreteUtExecution)
                            }

                            logger.debug { "concolicStrategy<${methodUnderTest}>: returned $concreteUtExecution" }
                        } catch (e: CancellationException) {
                            logger.debug(e) { "Cancellation happened" }
                        } catch (e: ConcreteExecutionFailureException) {
                            @CyberModify("filter emit") if (!onlyVulnerabilities) {
                                emitFailedConcreteExecutionResult(stateBefore, e)
                            }
                        } catch (e: Throwable) {
                            @CyberModify("filter emit") if (!onlyVulnerabilities) {
                                emit(UtError("Concrete execution failed", e))
                            }
                        }
                    }

                } else {
                    val state = pathSelector.poll()

                    // state is null in case states queue is empty
                    // or path selector exceed some limits (steps limit, for example)
                    if (state == null) {
                        // check do we have remaining states that we can execute concretely
                        val pathSelectorStatesForConcreteExecution = pathSelector
                            .remainingStatesForConcreteExecution
                            .map { it.withWrapperConsistencyChecks() }
                        if (pathSelectorStatesForConcreteExecution.isNotEmpty()) {
                            statesForConcreteExecution += pathSelectorStatesForConcreteExecution
                            logger.debug {
                                "${pathSelectorStatesForConcreteExecution.size} remaining states " +
                                        "were moved from path selector for concrete execution"
                            }
                            continue // the next step in while loop processes concrete states
                        } else {
                            break
                        }
                    }

                    state.executingTime += measureTimeMillis {
                        val newStates = try {
                            traverser.traverse(state)
                        } catch (ex: Throwable) {
                            @CyberModify("filter emit") if (!onlyVulnerabilities) {
                                emit(UtError(ex.description, ex))
                            }
                            return@measureTimeMillis
                        }
                        for (newState in newStates) {
                            @CyberNew("view new states") statePublisher.viewUpdate(newState)
                            when (newState.label) {
                                StateLabel.INTERMEDIATE -> pathSelector.offer(newState)
                                StateLabel.CONCRETE -> statesForConcreteExecution.add(newState)
                                StateLabel.TERMINAL -> {
                                    @CyberNew("ignore terminal state if the trace was not found yet")
                                    if (pathSelector is CyberSelector && !(pathSelector as CyberSelector).defaultSelection) {
                                        if (!(pathSelector as CyberSelector).traceFound()) {
                                            continue
                                        }

                                    }
                                    println("TERMINAL: ${newState.stmt}, from ${state.stmt}, method: ${newState}")
                                    consumeTerminalState(newState)
                                }
                            }
                        }

                        // Here job can be cancelled from within traverse, e.g. by using force mocking without Mockito.
                        // So we need to make it throw CancelledException by method below:
                        currentCoroutineContext().job.ensureActive()
                    }

                    // TODO: think about concise modifying globalGraph in Traverser and UtBotSymbolicEngine
                    globalGraph.visitNode(state)
                }
            }
        }
    }

    @CyberModify("org/utbot/engine/UtBotSymbolicEngine.kt", "filter terminal states")
    override suspend fun FlowCollector<UtResult>.consumeTerminalState(state: ExecutionState) {
        // some checks to be sure the state is correct
        require(state.label == StateLabel.TERMINAL) { "Can't process non-terminal state!" }
        require(!state.isInNestedMethod()) { "The state has to correspond to the MUT" }

        val memory = state.memory
        val solver = state.solver
        val parameters = state.parameters.map { it.value }
        val symbolicResult = requireNotNull(state.methodResult?.symbolicResult) { "The state must have symbolicResult" }
        val holder = requireNotNull(solver.lastStatus as? UtSolverStatusSAT) { "The state must be SAT!" }

        val predictedTestName = Predictors.testName.predict(state.path)
        Predictors.testName.provide(state.path, predictedTestName, "")

        // resolving
        val resolver = Resolver(
            hierarchy,
            memory,
            typeRegistry,
            typeResolver,
            holder,
            methodUnderTest,
            softMaxArraySize,
            traverser.objectCounter
        )

        val (modelsBefore, modelsAfter, instrumentation) = resolver.resolveModels(parameters)

        val symbolicExecutionResult = resolver.resolveResult(symbolicResult)

        @CyberNew("filter emit flag")
        val needEmit = !onlyVulnerabilities || isVulnerability(symbolicExecutionResult)


        val stateBefore = modelsBefore.constructStateForMethod(methodUnderTest)
        val stateAfter = modelsAfter.constructStateForMethod(methodUnderTest)
        require(stateBefore.parameters.size == stateAfter.parameters.size)

        val path = entryMethodPath(state)
        val symbolicUtExecution = UtSymbolicExecution(
            stateBefore = stateBefore,
            stateAfter = stateAfter,
            result = symbolicExecutionResult,
            instrumentation = instrumentation,
            path = path,
            fullPath = state.fullPath()
        )

        globalGraph.traversed(state)

        if (!UtSettings.useConcreteExecution ||
            // Can't execute concretely because overflows do not cause actual exceptions.
            // Still, we need overflows to act as implicit exceptions.
            (UtSettings.treatOverflowAsError && symbolicExecutionResult is UtOverflowFailure)
        ) {
            logger.debug {
                "processResult<${methodUnderTest}>: no concrete execution allowed, " +
                        "emit purely symbolic result $symbolicUtExecution"
            }
            @CyberNew("filter emit") if (!needEmit) return
            emit(symbolicUtExecution)
            return
        }

        // Check for lambda result as it cannot be emitted by concrete execution
        (symbolicExecutionResult as? UtExecutionSuccess)?.takeIf { it.model is UtLambdaModel }?.run {
            logger.debug {
                "processResult<${methodUnderTest}>: impossible to create concrete value for lambda result ($model), " +
                        "emit purely symbolic result $symbolicUtExecution"
            }

            @CyberModify("filter emit") if (onlyVulnerabilities) return
            emit(symbolicUtExecution)
            return
        }

        //It's possible that symbolic and concrete stateAfter/results are diverged.
        //So we trust concrete results more.
        try {
            logger.debug().bracket("processResult<$methodUnderTest>: concrete execution") {

                //this can throw CancellationException
                val concreteExecutionResult = concreteExecutor.executeConcretely(
                    methodUnderTest,
                    stateBefore,
                    instrumentation
                )

                if (concreteExecutionResult.violatesUtMockAssumption()) {
                    logger.debug { "Generated test case violates the UtMock assumption: $concreteExecutionResult" }
                    return
                }

                val concolicUtExecution = symbolicUtExecution.copy(
                    stateAfter = concreteExecutionResult.stateAfter,
                    result = concreteExecutionResult.result,
                    coverage = concreteExecutionResult.coverage
                )

                @CyberNew("filter emit") if (!needEmit) return
                emit(concolicUtExecution)
                logger.debug { "processResult<${methodUnderTest}>: returned $concolicUtExecution" }
            }
        } catch (e: ConcreteExecutionFailureException) {
            @CyberModify("filter emit") if (onlyVulnerabilities) return
            emitFailedConcreteExecutionResult(stateBefore, e)
        }
    }
}
