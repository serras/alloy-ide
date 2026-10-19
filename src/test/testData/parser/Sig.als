abstract sig Object {}
one sig Root, Home extends Object { f: set Object } { some f }
sig Dir in Object + univ {}
var sig Temp = Object {}
sig File { private var size: lone Int, disj a, b: seq File }
