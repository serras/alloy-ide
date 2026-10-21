package com.serranofp.alloy.viewer

/** A step of a trace, from one state to the next; the loop goes from the last state back to the loop target. */
data class Transition(val from: Int, val to: Int, val isLoop: Boolean) {
    companion object {
        /** The consecutive pairs of a trace of [traceLength] states, in order, then the loop to [loopState]. */
        fun lasso(traceLength: Int, loopState: Int): List<Transition> =
            (0 until traceLength - 1).map { Transition(it, it + 1, false) } + Transition(traceLength - 1, loopState, true)
    }
}
