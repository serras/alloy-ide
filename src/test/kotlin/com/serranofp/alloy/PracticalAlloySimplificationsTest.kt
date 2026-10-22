package com.serranofp.alloy

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.PracticalAlloyCorpus.Problem
import com.serranofp.alloy.simplifications.AlloyBooleanRules
import com.serranofp.alloy.simplifications.AlloyCardinalityRules
import com.serranofp.alloy.simplifications.AlloyEquivalenceRules
import com.serranofp.alloy.simplifications.AlloyOperator
import com.serranofp.alloy.simplifications.AlloyPiece
import com.serranofp.alloy.simplifications.AlloyQuantifierRules
import com.serranofp.alloy.simplifications.AlloyRelationalRules
import com.serranofp.alloy.simplifications.AlloyRewriteRule
import com.serranofp.alloy.simplifications.AlloyRewriteWalker
import com.serranofp.alloy.simplifications.AlloySimplifiableBooleanInspection
import com.serranofp.alloy.simplifications.AlloySimplifiableCardinalityInspection
import com.serranofp.alloy.simplifications.AlloySimplifiableQuantifierInspection
import com.serranofp.alloy.simplifications.AlloySimplifiableRelationalInspection
import com.serranofp.alloy.simplifications.AlloySimplifiableTemporalInspection
import com.serranofp.alloy.simplifications.AlloyTemporalRules
import com.serranofp.alloy.simplifications.onModelIfAny
import com.serranofp.alloy.simplifications.replace
import com.serranofp.alloy.simplifications.rule
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.ast.ExprBinary
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import kodkod.engine.satlab.SATFactory

/**
 * The simplification inspections over every model of the Practical Alloy book (see [PracticalAlloyModelsTest]):
 * each weak warning they report is listed and reviewed. Every rewrite of the simplifications and of the
 * intentions, on any node where one applies, keeps the model type-checking, and keeps the meaning of the
 * predicates without parameters it is in.
 */
class PracticalAlloySimplificationsTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = PracticalAlloyCorpus.TEST_DATA

    private val inspections = listOf(
        AlloySimplifiableBooleanInspection(),
        AlloySimplifiableCardinalityInspection(),
        AlloySimplifiableTemporalInspection(),
        AlloySimplifiableQuantifierInspection(),
        AlloySimplifiableRelationalInspection(),
    )

    /** The rules of the simplifications and of the intentions. */
    private val rules: List<AlloyRewriteRule> = listOf(
        AlloyBooleanRules.ALL, AlloyCardinalityRules.ALL, AlloyTemporalRules.ALL, AlloyQuantifierRules.ALL, AlloyRelationalRules.ALL,
        AlloyEquivalenceRules.DE_MORGAN_RULES, AlloyEquivalenceRules.OR_TO_IMPLIES, AlloyEquivalenceRules.IMPLIES_TO_OR,
        AlloyEquivalenceRules.QUANTIFIER_NEGATION, AlloyEquivalenceRules.TEMPORAL_NEGATION, AlloyEquivalenceRules.TEMPORAL_DISTRIBUTION,
        AlloyEquivalenceRules.TRANSPOSE_JOIN,
    ).flatten()

    private fun none(set: String) = Problem("AlloySimplifiableCardinality", set, "Comparison with 'none'")

    private val someDir = Problem("AlloySimplifiableQuantifier", "some d : Dir | d in Entry.object", "Quantified membership")

    /**
     * By path under `practicalalloy`. The runs that spell out an instance write `File = none` for an empty
     * signature, aligned with the lines around it, where `no File` would do.
     */
    private val expected = mapOf(
        // The style the chapter improves on, with `disj`
        "structural-modeling/a-question-of-style/filesystem.als" to listOf(
            Problem("AlloySimplifiableQuantifier", "all x, y : Dir | x != y implies no (x.entries & y.entries)", "Distinct variables"),
        ),
        "structural-modeling/instance_04_05/filesystem.als" to listOf(none("File = none"), none("File  = none")),
        "structural-modeling/instance_06/filesystem.als" to listOf(none("File    = none"), none("Name    = none")),
        "structural-modeling/instance_10/filesystem.als" to listOf(none("File    = none")),
        "structural-topics/commands/instance_01/filesystem.als" to listOf(none("File    = none")),
        // `some Dir & Entry.object`, which the book writes with a variable to show skolemization
        "structural-topics/model-finding/instance_01_02/filesystem.als" to listOf(someDir, none("File    = none")),
        "structural-topics/model-finding/skolemization/filesystem.als" to listOf(someDir),
        // `entries = none->none` is about a relation, and is not reported
        "structural-topics/subset-signatures/instance_03/filesystem.als" to listOf(
            none("File    = none"), none("Entry   = none"), none("Name    = none"), none("Tagged  = none"),
        ),
    )

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(*inspections.toTypedArray())
    }

    fun testStructuralModeling() = checkModels("structural-modeling")

    fun testStructuralTopics() = checkModels("structural-topics")

    fun testBehavioralModeling() = checkModels("behavioral-modeling")

    fun testBehavioralTopics() = checkModels("behavioral-topics")

    fun testProtocolDesign() = checkModels("protocol-design")

    private fun checkModels(folder: String) {
        val models = PracticalAlloyCorpus.models(myFixture, folder)
        assertNotEmpty(models)
        val ids = inspections.map { it.shortName }.toSet()
        var rewritten = 0
        val failures = models.flatMap { (path, model) ->
            myFixture.configureFromExistingVirtualFile(model)
            val problems = PracticalAlloyCorpus.problems(myFixture, ids, HighlightSeverity.WEAK_WARNING)
            val unexpected = if (problems != expected[path].orEmpty()) listOf("\"$path\" to listOf(${problems.joinToString { it.code() }}),") else emptyList()
            val rewrites = rewrites()
            rewritten += rewrites.size
            unexpected + brokenRewrites(path, rewrites) + changedMeanings(path, rewrites)
        }
        // The rules do apply to the book's models: the checks are not vacuous
        assertTrue("Only $rewritten rewrites in $folder", rewritten > models.size)
        // Most of them in paragraphs whose meaning the analyzer checks
        assertTrue("Only $checked of $rewritten rewrites in $folder are checked", checked * 4 > rewritten * 3)
        assertEmpty(failures.joinToString("\n"), failures)
    }

    /** The positive control of the meaning check: the converse of an implication, which type-checks, is found out. */
    fun testMeaningCheckFindsAWrongRewrite() {
        val converse = rule { node ->
            val implies = (node as? ExprBinary)?.takeIf { it.op == ExprBinary.Op.IMPLIES && written(it) } ?: return@rule null
            replace(node, AlloyPiece.Op(AlloyOperator.IMPLIES, slice(implies.right) ?: return@rule null, slice(implies.left) ?: return@rule null), node)
        }
        val path = "behavioral-modeling/verifying-expected-properties/filesharing.als"
        val (_, model) = PracticalAlloyCorpus.models(myFixture, "behavioral-modeling").single { it.first == path }
        myFixture.configureFromExistingVirtualFile(model)
        val rewrites = rewrites(listOf(converse))
        assertNotEmpty(rewrites)
        assertEmpty(brokenRewrites(path, rewrites))
        assertNotEmpty(changedMeanings(path, rewrites))
    }

    /**
     * The paragraph of a rewrite, whose meaning it must keep: [decl] is its declaration and [body] where its body
     * starts, at its `{`. A predicate is compared by its [name], for all values of its [parameters], if any; a fact
     * or an assertion is copied to a predicate, a fact also no longer holding then.
     */
    private class Paragraph(val decl: TextRange, val body: Int, val kind: String, val name: String, val parameters: String?) {
        /** The names of the [parameters]. */
        val arguments: String? = parameters?.split(',')?.joinToString { it.substringBefore(':').trim() }
    }

    /** One rewrite of the current model: the text it replaces, by what, and its [paragraph] if its meaning can be checked. */
    private class Rewrite(val range: TextRange, val text: String, val fixName: String, val paragraph: Paragraph?)

    /**
     * Every rewrite that [rules] make of any written node of the current model, as the intentions can be invoked
     * on any of them; none if it does not type-check, as the book shows some models that do not.
     */
    private fun rewrites(rules: List<AlloyRewriteRule> = this.rules): List<Rewrite> = myFixture.onModelIfAny { source, model ->
        val found = ArrayList<Rewrite>()
        val record = rule { node ->
            if (!written(node)) return@rule null
            for (rule in rules) {
                val result = rewrite(rule, node) ?: continue
                val decl = paragraph.textRange
                val header = HEADER.find(paragraph.text)?.takeIf { it.groupValues[1] != "pred" || it.groupValues[2].isNotEmpty() }
                val checked = header?.let {
                    Paragraph(decl, decl.startOffset + it.range.last, it.groupValues[1], it.groupValues[2], it.groups[4]?.value)
                }
                found += Rewrite(result.range, result.text, result.fixName, checked)
            }
            null
        }
        AlloyRewriteWalker(source, listOf(record)).walk(model.module)
        found
    } ?: emptyList()

    private fun Rewrite.applied(text: String): String = text.replaceRange(range.startOffset, range.endOffset, this.text)

    /**
     * The rewrites that, applied one at a time to a copy of the current model's text, leave it failing to type-check.
     * They are first applied together, as many as do not overlap at a time, and only one by one if that fails.
     */
    private fun brokenRewrites(path: String, rewrites: List<Rewrite>): List<String> {
        val text = myFixture.file.text
        val batches = ArrayList<MutableList<Rewrite>>()
        for (rewrite in rewrites.sortedBy { it.range.startOffset }) {
            val batch = batches.firstOrNull { batch -> batch.last().range.endOffset <= rewrite.range.startOffset }
            if (batch != null) batch += rewrite else batches += mutableListOf(rewrite)
        }
        return batches.flatMap { batch ->
            val all = batch.sortedByDescending { it.range.startOffset }.fold(text) { fixed, rewrite -> rewrite.applied(fixed) }
            if (PracticalAlloyCorpus.parse(path, all).isSuccess) return@flatMap emptyList()
            batch.mapNotNull { rewrite ->
                PracticalAlloyCorpus.parse(path, rewrite.applied(text)).exceptionOrNull()?.let { "$path: '${rewrite.fixName}' at ${rewrite.range} gives an error: ${it.message}" }
            }
        }
    }

    /** Whether the analyzer finds a state where some of [rewrites] change the meaning of their paragraph; null if it cannot tell. */
    private fun differ(path: String, text: String, rewrites: List<Rewrite>): Boolean? {
        val paragraphs = rewrites.map { it.paragraph!! }.distinctBy { it.decl.startOffset }
        fun old(paragraph: Paragraph): String =
            if (paragraph.kind == "pred") paragraph.name else "rewritten__old${paragraphs.indexOfFirst { it.decl == paragraph.decl }}"
        fun asPredicate(paragraph: Paragraph, decl: String, name: String) =
            "pred $name${paragraph.parameters?.let { "[$it]" } ?: ""} " + decl.substring(paragraph.body - paragraph.decl.startOffset)
        // A fact becomes a predicate, which no longer holds; an assertion is copied, as a `check` may name it
        var model = text
        for (paragraph in paragraphs.filter { it.kind == "fact" }.sortedByDescending { it.decl.startOffset }) {
            model = model.replaceRange(paragraph.decl.startOffset, paragraph.decl.endOffset, asPredicate(paragraph, paragraph.decl.substring(text), old(paragraph)))
        }
        val copies = paragraphs.filter { it.kind == "assert" }.joinToString("") { "\n" + asPredicate(it, it.decl.substring(text), old(it)) }
        val preds = rewrites.withIndex().joinToString("") { (j, rewrite) ->
            val paragraph = rewrite.paragraph!!
            val inner = rewrite.range.shiftLeft(paragraph.decl.startOffset)
            "\n" + asPredicate(paragraph, paragraph.decl.substring(text).replaceRange(inner.startOffset, inner.endOffset, rewrite.text), "rewritten__$j")
        }
        val same = rewrites.withIndex().joinToString(" and ") { (j, rewrite) ->
            val paragraph = rewrite.paragraph!!
            val arguments = paragraph.arguments?.let { "[$it]" } ?: ""
            val iff = "${old(paragraph)}$arguments iff rewritten__$j$arguments"
            if (paragraph.parameters == null) "($iff)" else "(all ${paragraph.parameters} | $iff)"
        }
        val module = PracticalAlloyCorpus.parse(path, "$model$copies$preds\ncheck rewritten__check { always ($same) } for 3 but 1..3 steps\n").getOrNull()
            ?: return null
        val command = module.allCommands.single { it.label == "rewritten__check" }
        val options = A4Options().apply { solver = SATFactory.get("sat4j") }
        return try {
            TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.allReachableSigs, command, options).satisfiable()
        } catch (_: Err) {
            null
        }
    }

    /**
     * The rewrites that change the meaning of their paragraph, as the analyzer checks in every state, for the
     * overall scope of 3 and traces of up to 3 steps. All are checked at once, then by paragraph, then one by
     * one where that fails. Where the analyzer cannot translate the check, as for a predicate with a parameter
     * that is a set, the rewrites of the paragraph are only type-checked; [checked] counts the others.
     */
    private fun changedMeanings(path: String, rewrites: List<Rewrite>): List<String> {
        val text = myFixture.file.text
        val candidates = rewrites.filter { it.paragraph != null }
        if (candidates.isEmpty()) return emptyList()
        if (differ(path, text, candidates) == false) {
            checked += candidates.size
            return emptyList()
        }
        return candidates.groupBy { it.paragraph!!.decl }.values.flatMap { group ->
            val differs = differ(path, text, group) ?: return@flatMap emptyList()
            checked += group.size
            if (!differs) return@flatMap emptyList()
            group.filter { differ(path, text, listOf(it)) == true }
                .map { "$path: '${it.fixName}' at ${it.range} changes the meaning of ${it.paragraph!!.kind} ${it.paragraph.name}" }
        }
    }

    /** The rewrites whose meaning was checked. */
    private var checked = 0

    private companion object {
        /** The header of a predicate, fact or assertion, up to its `{`: the kind, the name and the parameters, if any. */
        val HEADER = Regex("""^(pred|fact|assert)\s*(\w*)\s*(\[([^\[\]()]*)])?\s*\{""")
    }
}
