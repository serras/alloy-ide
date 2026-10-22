package com.serranofp.alloy.inspections

import com.serranofp.alloy.PracticalAlloyCorpus
import com.serranofp.alloy.semantics.AlloyModelResult
import com.serranofp.alloy.semantics.AlloyModules
import com.serranofp.alloy.semantics.AlloyPositions
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.ast.Command
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.ast.Sig.PrimSig
import edu.mit.csail.sdg.parser.CompModule
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import junit.framework.TestCase
import java.io.File

/**
 * [AlloyCommandBounds] agrees with the scopes that the analyzer computes when it translates each command of the
 * book's models, which it reports through [A4Reporter.actualScopes] before solving. That map also holds the
 * sequence bound, as the scope of `seq/Int`.
 */
class AlloyCommandBoundsTest : TestCase() {
    private data class Bounds(val sigs: Map<String, Pair<Int, Boolean>>, val seq: Int)

    /** Stops the translation once the scopes are known. */
    private class Computed(val bounds: Bounds) : RuntimeException()

    private var rejection: String? = null

    /**
     * Commands of the book that the analyzer rejects when it runs them: they scope `Node` but give neither an
     * overall scope nor one for `Message`. [AlloyCommandBounds] knows no bound for them either.
     */
    private val rejected = setOf(
        "protocol-design/explicit-messages-as-signatures/leaderelection.als: example3",
        "protocol-design/explicit-messages-as-signatures/leaderelection.als: eventually_elected_1node",
        "protocol-design/instance_17_18/leaderelection.als: example3",
        "protocol-design/instance_19/leaderelection.als: example3",
        "protocol-design/instance_19/leaderelection.als: eventually_elected_1node",
    )

    private fun analyzer(module: CompModule, command: Command): Bounds? {
        val reporter = object : A4Reporter() {
            override fun actualScopes(sigs: Iterable<Sig>, sig2scope: Map<PrimSig, Int>, exact: Set<Sig>) {
                val user = userSigs(sigs).associate { it.label to (sig2scope.getValue(it) to (it in exact)) }
                throw Computed(Bounds(user, sig2scope.getValue(Sig.SEQIDX)))
            }
        }
        rejection = null
        return try {
            TranslateAlloyToKodkod.execute_command(reporter, module.allReachableSigs, command, A4Options())
            error("$command was translated without reporting its scopes")
        } catch (e: Err) {
            rejection = e.msg
            (e.cause as? Computed)?.bounds
        }
    }

    private fun ours(module: CompModule, command: Command): Bounds? {
        val bounds = AlloyCommandBounds(command, module.allReachableSigs.toList())
        val user = userSigs(module.allReachableSigs).associate { sig ->
            val bound = bounds.bound(sig) ?: return null
            sig.label to (bound.n to bound.exact)
        }
        return Bounds(user, bounds.seq ?: return null)
    }

    /** Top-level signatures declared in the models, not in the library. */
    private fun userSigs(sigs: Iterable<Sig>): List<PrimSig> =
        sigs.filterIsInstance<PrimSig>().filter { !it.builtin && it.isTopLevel && !AlloyPositions.isLibrary(it.pos.filename) }

    /**
     * The top-level signatures whose bound has a source that does not match [command]: the analyzer has no
     * notion of it, so it is checked against what the command writes and the signature declares.
     */
    private fun wrongSources(module: CompModule, command: Command): List<String> {
        val bounds = AlloyCommandBounds(command, module.allReachableSigs.toList())
        return userSigs(module.allReachableSigs).mapNotNull { sig ->
            val bound = bounds.bound(sig) ?: return@mapNotNull null
            val written = command.scope.firstOrNull { it.sig === sig }
            val right = when (val source = bound.source) {
                is AlloyCommandBounds.Source.Explicit -> source.scope === written
                AlloyCommandBounds.Source.Overall -> written == null && command.overall >= 0 && bound.n == command.overall
                AlloyCommandBounds.Source.Default -> written == null && command.overall < 0 && command.scope.isEmpty() && bound.n == 3
                AlloyCommandBounds.Source.Fixed -> written == null && (sig.isOne != null || sig.isLone != null || sig.isEnum != null)
                AlloyCommandBounds.Source.Inherited -> written == null
            }
            if (right) null else "${sig.label}: $bound"
        }
    }

    private fun checkModels(folder: String) {
        val root = File("${PracticalAlloyCorpus.TEST_DATA}/practicalalloy")
        val models = File(root, folder).walkTopDown().filter { it.extension == "als" }.sorted().toList()
        assertFalse(models.isEmpty())
        var commands = 0
        val failures = models.flatMap { model ->
            val module = CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, model.path)
            module.allCommands.mapNotNull { command ->
                commands++
                val expected = analyzer(module, command)
                val actual = ours(module, command)
                val name = "${model.relativeTo(root).invariantSeparatorsPath}: ${command.label}"
                // So that the comparison is never between two nulls by accident
                if ((expected == null) != (name in rejected)) "$name: rejected is ${expected == null} (${rejection}), expected ${name in rejected}"
                else if (expected != actual) "$name: $command is $actual, expected $expected"
                else wrongSources(module, command).takeIf { it.isNotEmpty() }?.let { "$name: $command has wrong sources $it" }
            }
        }
        assertTrue(commands > 0)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    fun testProtocolDesign() = checkModels("protocol-design")

    fun testSequences() = checkModels("behavioral-topics/sequences")

    fun testSources() {
        val model = """
            open util/ordering[Id]
            sig Node { id: one Id }
            sig Id {}
            abstract sig Message {}
            sig A, B extends Message {}
            one sig Network {}
            run scoped {} for 3 but exactly 2 Node, 2 A, 1 B
            run plain {}
        """.trimIndent()
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
        fun bound(label: String, name: String): AlloyCommandBounds.Bound {
            val command = module.allCommands.single { it.label == label }
            val bounds = AlloyCommandBounds(command, module.allReachableSigs.toList())
            return bounds.bound(module.allReachableSigs.single { it.label == "this/$name" })!!
        }
        val node = bound("scoped", "Node")
        assertEquals(2, node.n)
        assertTrue(node.exact)
        assertTrue(node.source is AlloyCommandBounds.Source.Explicit)
        assertEquals(AlloyCommandBounds.Bound(3, true, AlloyCommandBounds.Source.Overall), bound("scoped", "Id"))
        assertEquals(AlloyCommandBounds.Bound(3, false, AlloyCommandBounds.Source.Inherited), bound("scoped", "Message"))
        assertEquals(AlloyCommandBounds.Bound(1, true, AlloyCommandBounds.Source.Fixed), bound("scoped", "Network"))
        assertEquals(AlloyCommandBounds.Bound(3, false, AlloyCommandBounds.Source.Default), bound("plain", "Node"))
    }

    fun testSeqBound() {
        val model = "sig A {}\nrun written {} for 3 but 2 seq\nrun overall {} for 5\nrun default {}\nrun capped {} for 9"
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
        val seq = module.allCommands.associate { it.label to AlloyCommandBounds(it, module.allReachableSigs.toList()).seq }
        assertEquals(mapOf("written" to 2, "overall" to 5, "default" to 4, "capped" to 7), seq)
    }

    fun testRaisedScopeIsInherited() {
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, "sig A {}\none sig B, C, D, E extends A {}\nrun r {} for 3")
        val command = module.allCommands.single()
        val a = module.allReachableSigs.single { it.label == "this/A" }
        // `ScopeComputer.computeLowerBound` raises it to its four `one` children
        assertEquals(4 to false, analyzer(module, command)!!.sigs.getValue("this/A"))
        assertEquals(AlloyCommandBounds.Bound(4, false, AlloyCommandBounds.Source.Inherited), AlloyCommandBounds(command, module.allReachableSigs.toList()).bound(a))
    }

    fun testUnknownWhereTheAnalyzerRejectsTheIntegersOrStrings() {
        val model = "sig A {}\nrun strings {} for 3 but 3 String\nrun bitwidth {} for 3 but 31 int\nrun sequence {} for 3 but 8 seq\n" +
            "run exactStrings {} for 3 but exactly 3 String\nrun longSequence {} for 3 but 8 seq, 5 int"
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
        val a = module.allReachableSigs.single { it.label == "this/A" }
        val known = module.allCommands.associate { command ->
            val bounds = AlloyCommandBounds(command, module.allReachableSigs.toList())
            assertEquals(command.label, analyzer(module, command) != null, bounds.bound(a) != null)
            command.label to (bounds.bound(a) != null && bounds.seq != null)
        }
        assertEquals(mapOf("strings" to false, "bitwidth" to false, "sequence" to false, "exactStrings" to true, "longSequence" to true), known)
    }

    fun testBoundsAreComputedOncePerModel() {
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, "sig A {}\nrun a {} for 2 => run b {} for 3")
        val model = AlloyModelResult.Model(module, emptyList(), AlloyModules(emptyMap(), emptyMap()))
        val last = module.allCommands.single()
        assertSame(AlloyCommandBounds.of(model, last), AlloyCommandBounds.of(model, last))
        assertSame(AlloyCommandBounds.of(model, last.parent), AlloyCommandBounds.of(model, last.parent))
        val a = module.allReachableSigs.single { it.label == "this/A" }
        assertEquals(listOf(3, 2), listOf(last, last.parent).map { AlloyCommandBounds.of(model, it).bound(a)!!.n })
    }

    fun testUnknownWhenTheAnalyzerRejectsTheScope() {
        val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, "sig A {}\nsig B {}\nrun r {} for 2 A")
        val bounds = AlloyCommandBounds(module.allCommands.single(), module.allReachableSigs.toList())
        assertNull(bounds.bound(module.allReachableSigs.single { it.label == "this/A" }))
    }
}
