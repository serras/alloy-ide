package com.serranofp.alloy.simplifications

class AlloyDeMorganIntention : AlloyRewriteIntention(AlloyEquivalenceRules.DE_MORGAN_RULES, "simplification.de.morgan")

class AlloyOrToImpliesIntention : AlloyRewriteIntention(AlloyEquivalenceRules.OR_TO_IMPLIES, "simplification.intention.or.to.implies")

class AlloyImpliesToOrIntention : AlloyRewriteIntention(AlloyEquivalenceRules.IMPLIES_TO_OR, "simplification.intention.implies.to.or")

class AlloyQuantifierNegationIntention : AlloyRewriteIntention(AlloyEquivalenceRules.QUANTIFIER_NEGATION, "simplification.intention.quantifier.negation")

class AlloyTemporalNegationIntention : AlloyRewriteIntention(AlloyEquivalenceRules.TEMPORAL_NEGATION, "simplification.intention.temporal.negation")

class AlloyTemporalDistributionIntention : AlloyRewriteIntention(AlloyEquivalenceRules.TEMPORAL_DISTRIBUTION, "simplification.intention.temporal.distribution")

class AlloyTransposeJoinIntention : AlloyRewriteIntention(AlloyEquivalenceRules.TRANSPOSE_JOIN, "simplification.intention.transpose.join")
