package com.serranofp.alloy.simplifications

class AlloyDeMorganIntention : AlloyRewriteIntention(AlloyEquivalenceRules.DE_MORGAN_RULES, "Apply De Morgan's law")

class AlloyOrToImpliesIntention : AlloyRewriteIntention(AlloyEquivalenceRules.OR_TO_IMPLIES, "Replace 'or' with 'implies'")

class AlloyImpliesToOrIntention : AlloyRewriteIntention(AlloyEquivalenceRules.IMPLIES_TO_OR, "Replace 'implies' with 'or'")

class AlloyQuantifierNegationIntention : AlloyRewriteIntention(AlloyEquivalenceRules.QUANTIFIER_NEGATION, "Move negation into or out of quantifier")

class AlloyTemporalNegationIntention : AlloyRewriteIntention(AlloyEquivalenceRules.TEMPORAL_NEGATION, "Move negation into or out of temporal operator")

class AlloyTemporalDistributionIntention : AlloyRewriteIntention(AlloyEquivalenceRules.TEMPORAL_DISTRIBUTION, "Distribute or factor out temporal operator")

class AlloyTransposeJoinIntention : AlloyRewriteIntention(AlloyEquivalenceRules.TRANSPOSE_JOIN, "Swap join with transpose")
