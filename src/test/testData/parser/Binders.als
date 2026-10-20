sig A { r: A -> A }
fun f: set A { { a: A, disj b, c: A | a->b->c in r } }
fact { all disj x, y: A, z: A { x->y in r } }
fact { sum n: A | #n.r }
fact { let a = A, b = { x: A | some x.r } { a = b } }
