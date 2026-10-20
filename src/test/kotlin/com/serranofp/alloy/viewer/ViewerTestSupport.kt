package com.serranofp.alloy.viewer

import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.A4Solution
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod

/** The solution of the first command of [model], solved here (the engine's own tests cover the engine). */
fun solve(model: String): A4Solution {
    val module = CompUtil.parseEverything_fromString(A4Reporter.NOP, model)
    return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.allReachableSigs, module.allCommands.first(), A4Options())
}

const val STATIC = "sig Node { succ: set Node }\nrun { some succ } for 2"

const val ORDERING = "open util/ordering[State]\nsig State { v: set State }\nrun {} for 3"

/** Exactly one trace: states 0, 1 and 2, then back to state 1. */
const val LASSO = "abstract sig V {}\none sig V1, V2, V3 extends V {}\nvar one sig Cur in V {}\n" +
    "fact { Cur = V1 and after Cur = V2 and always (Cur = V2 implies after Cur = V3) and always (Cur = V3 implies after Cur = V2) }\n" +
    "run {} for 3 but 1..3 steps"

/** Many traces of up to 4 states. */
const val TRACES = "sig A {}\nvar sig X in A {}\nrun { some X and after no X } for 3 but 1..4 steps"
