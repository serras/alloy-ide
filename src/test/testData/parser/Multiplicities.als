sig Node { succ: one Node, id: one Id, var inbox: set Id, disj a, b: disj lone Node }
pred p [n: one Node, m: some Node] { all x: one Node, y: Node | some x.succ }
